//! Restart a failed capture device without restarting the stream or sequence counter.

use std::time::Duration;

use async_trait::async_trait;
use tokio::time::{sleep_until, Instant};
use tracing::{info, warn};

use super::{AudioCaptureSource, AudioFormat, AudioFrame, CaptureError, CaptureSourceState};

const INITIAL_RETRY_DELAY: Duration = Duration::from_millis(250);
const MAX_RETRY_DELAY: Duration = Duration::from_secs(5);

pub struct RecoveringAudioSource {
    inner: Box<dyn AudioCaptureSource>,
    running: bool,
    retry_at: Option<Instant>,
    next_delay: Duration,
    attempts: u32,
}

impl RecoveringAudioSource {
    pub fn new(inner: Box<dyn AudioCaptureSource>) -> Self {
        Self {
            inner,
            running: false,
            retry_at: None,
            next_delay: INITIAL_RETRY_DELAY,
            attempts: 0,
        }
    }

    fn schedule_recovery(&mut self, error: &CaptureError) {
        let delay = self.next_delay;
        self.retry_at = Some(Instant::now() + delay);
        self.next_delay = delay.saturating_mul(2).min(MAX_RETRY_DELAY);
        warn!(
            source = self.inner.source_name(),
            error = %error,
            retry_in_ms = delay.as_millis() as u64,
            "capture recovery scheduled"
        );
    }
}

#[async_trait]
impl AudioCaptureSource for RecoveringAudioSource {
    async fn start(&mut self) -> Result<(), CaptureError> {
        self.inner.start().await?;
        self.running = true;
        self.retry_at = None;
        self.next_delay = INITIAL_RETRY_DELAY;
        self.attempts = 0;
        Ok(())
    }

    async fn read_frame(&mut self) -> Result<AudioFrame, CaptureError> {
        if !self.running {
            return Err(CaptureError::NotStarted);
        }
        if let Some(deadline) = self.retry_at {
            // The enclosing transport's shutdown select can cancel this wait.
            sleep_until(deadline).await;
            self.attempts = self.attempts.saturating_add(1);
            info!(
                source = self.inner.source_name(),
                attempt = self.attempts,
                "restarting failed audio capture"
            );
            if let Err(error) = self.inner.stop().await {
                warn!(error = %error, "capture cleanup before restart failed");
            }
            if let Err(error) = self.inner.start().await {
                self.schedule_recovery(&error);
                return Err(error);
            }
            self.retry_at = None;
        }

        match self.inner.read_frame().await {
            Ok(frame) => {
                if self.attempts != 0 {
                    info!(
                        source = self.inner.source_name(),
                        attempts = self.attempts,
                        "audio capture recovered"
                    );
                }
                // A successful start alone is insufficient: require a frame
                // before resetting backoff, including valid silence/no-packet frames.
                self.next_delay = INITIAL_RETRY_DELAY;
                self.attempts = 0;
                Ok(frame)
            }
            Err(error) => {
                self.schedule_recovery(&error);
                Err(error)
            }
        }
    }

    async fn stop(&mut self) -> Result<(), CaptureError> {
        self.running = false;
        self.retry_at = None;
        self.next_delay = INITIAL_RETRY_DELAY;
        self.attempts = 0;
        self.inner.stop().await
    }

    fn format(&self) -> AudioFormat {
        self.inner.format()
    }

    fn state(&self) -> CaptureSourceState {
        self.inner.state()
    }

    fn source_name(&self) -> &'static str {
        self.inner.source_name()
    }

    fn device_name(&self) -> Option<String> {
        self.inner.device_name()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::audio_capture::PacketKind;
    use std::collections::VecDeque;
    use std::sync::{Arc, Mutex};

    #[derive(Clone, Copy)]
    enum Action {
        Audio,
        Silence,
        NoPacket,
        Fail,
    }

    #[derive(Default)]
    struct Probe {
        starts: u32,
        stops: u32,
        reads: u32,
        start_failures: u32,
        actions: VecDeque<Action>,
    }

    struct FakeSource {
        probe: Arc<Mutex<Probe>>,
        state: CaptureSourceState,
    }

    #[async_trait]
    impl AudioCaptureSource for FakeSource {
        async fn start(&mut self) -> Result<(), CaptureError> {
            let mut probe = self.probe.lock().unwrap();
            probe.starts += 1;
            if probe.start_failures != 0 {
                probe.start_failures -= 1;
                self.state = CaptureSourceState::Failed;
                return Err(CaptureError::DefaultDeviceNotFound);
            }
            self.state = CaptureSourceState::Started;
            Ok(())
        }

        async fn read_frame(&mut self) -> Result<AudioFrame, CaptureError> {
            if self.state != CaptureSourceState::Started {
                return Err(CaptureError::NotStarted);
            }
            let mut probe = self.probe.lock().unwrap();
            probe.reads += 1;
            let action = probe.actions.pop_front().unwrap_or(Action::NoPacket);
            if matches!(action, Action::Fail) {
                self.state = CaptureSourceState::Failed;
                return Err(CaptureError::ReadBufferFailed("device invalidated".into()));
            }
            let mut frame = AudioFrame::silence(123, self.format());
            match action {
                Action::Audio => {
                    frame.samples_f32.fill(0.25);
                    frame.is_silence = false;
                    frame.peak = 0.25;
                    frame.rms = 0.25;
                    frame.packet_kind = PacketKind::AudioPacket;
                }
                Action::NoPacket => frame.packet_kind = PacketKind::NoPacket,
                Action::Silence => {}
                Action::Fail => unreachable!(),
            }
            Ok(frame)
        }

        async fn stop(&mut self) -> Result<(), CaptureError> {
            self.probe.lock().unwrap().stops += 1;
            self.state = CaptureSourceState::Stopped;
            Ok(())
        }

        fn format(&self) -> AudioFormat {
            AudioFormat::default()
        }
        fn state(&self) -> CaptureSourceState {
            self.state
        }
        fn source_name(&self) -> &'static str {
            "mock_loopback"
        }
        fn device_name(&self) -> Option<String> {
            Some("test endpoint".into())
        }
    }

    fn source(actions: &[Action]) -> (RecoveringAudioSource, Arc<Mutex<Probe>>) {
        let probe = Arc::new(Mutex::new(Probe {
            actions: actions.iter().copied().collect(),
            ..Probe::default()
        }));
        let inner = FakeSource {
            probe: Arc::clone(&probe),
            state: CaptureSourceState::Created,
        };
        (RecoveringAudioSource::new(Box::new(inner)), probe)
    }

    #[tokio::test(start_paused = true)]
    async fn transient_read_failure_recovers_without_stream_restart() {
        let (mut source, probe) = source(&[Action::Audio, Action::Fail, Action::Audio]);
        source.start().await.unwrap();
        assert!(!source.read_frame().await.unwrap().is_silence);
        assert!(source.read_frame().await.is_err());
        let started = Instant::now();
        assert!(!source.read_frame().await.unwrap().is_silence);
        assert_eq!(started.elapsed(), INITIAL_RETRY_DELAY);
        assert_eq!(source.state(), CaptureSourceState::Started);
        assert_eq!(source.device_name().as_deref(), Some("test endpoint"));
        let probe = probe.lock().unwrap();
        assert_eq!((probe.starts, probe.stops, probe.reads), (2, 1, 3));
    }

    #[tokio::test(start_paused = true)]
    async fn ordinary_pause_silence_and_resume_never_restart_the_device() {
        let mut actions = vec![Action::Audio];
        for _ in 0..500 {
            actions.extend([Action::Silence, Action::NoPacket]);
        }
        actions.push(Action::Audio);
        let (mut source, probe) = source(&actions);
        source.start().await.unwrap();
        assert!(!source.read_frame().await.unwrap().is_silence);
        for _ in 0..1000 {
            assert!(source.read_frame().await.unwrap().is_silence);
        }
        assert!(!source.read_frame().await.unwrap().is_silence);
        let probe = probe.lock().unwrap();
        assert_eq!((probe.starts, probe.stops), (1, 0));
    }

    #[tokio::test(start_paused = true)]
    async fn failed_restarts_back_off_and_eventually_recover() {
        let (mut source, probe) = source(&[Action::Fail, Action::Audio]);
        source.start().await.unwrap();
        assert!(source.read_frame().await.is_err());
        probe.lock().unwrap().start_failures = 6;
        for millis in [250, 500, 1000, 2000, 4000, 5000] {
            let started = Instant::now();
            assert!(source.read_frame().await.is_err());
            assert_eq!(started.elapsed(), Duration::from_millis(millis));
        }
        let started = Instant::now();
        assert!(!source.read_frame().await.unwrap().is_silence);
        assert_eq!(started.elapsed(), MAX_RETRY_DELAY);
    }

    #[tokio::test(start_paused = true)]
    async fn successful_start_with_immediate_read_failure_keeps_backoff() {
        let (mut source, probe) = source(&[
            Action::Fail,
            Action::Fail,
            Action::Fail,
            Action::Audio,
            Action::Fail,
            Action::Audio,
        ]);
        source.start().await.unwrap();
        assert!(source.read_frame().await.is_err());
        for millis in [250, 500] {
            let started = Instant::now();
            assert!(source.read_frame().await.is_err());
            assert_eq!(started.elapsed(), Duration::from_millis(millis));
        }
        let started = Instant::now();
        assert!(!source.read_frame().await.unwrap().is_silence);
        assert_eq!(started.elapsed(), Duration::from_millis(1000));
        assert!(source.read_frame().await.is_err());
        let started = Instant::now();
        assert!(!source.read_frame().await.unwrap().is_silence);
        assert_eq!(started.elapsed(), INITIAL_RETRY_DELAY);
        assert_eq!(probe.lock().unwrap().starts, 5);
    }

    #[tokio::test(start_paused = true)]
    async fn explicit_stop_cancels_pending_recovery() {
        let (mut source, probe) = source(&[Action::Fail]);
        source.start().await.unwrap();
        assert!(source.read_frame().await.is_err());
        source.stop().await.unwrap();
        assert!(matches!(
            source.read_frame().await,
            Err(CaptureError::NotStarted)
        ));
        assert_eq!(probe.lock().unwrap().starts, 1);
    }

    #[tokio::test(start_paused = true)]
    async fn shutdown_can_cancel_the_backoff_wait() {
        let (mut source, probe) = source(&[Action::Fail, Action::Audio]);
        source.start().await.unwrap();
        assert!(source.read_frame().await.is_err());
        assert!(
            tokio::time::timeout(Duration::from_millis(10), source.read_frame())
                .await
                .is_err()
        );
        source.stop().await.unwrap();
        assert_eq!(probe.lock().unwrap().starts, 1);
        assert_eq!(source.state(), CaptureSourceState::Stopped);
    }
}
