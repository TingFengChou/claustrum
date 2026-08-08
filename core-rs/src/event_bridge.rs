//! Host-testable lifecycle boundary used by the Android JNI event bridge.
//!
//! JNI callers receive opaque numeric handles instead of Rust pointers. Invalid or
//! already-destroyed handles are rejected, so a Kotlin lifecycle mistake cannot
//! dereference freed memory. Each emitted string is one `event.schema.json` event.

use std::collections::BTreeMap;
use std::fmt;

use crate::events::{EventConfig, EventConfigError, EventEngine, Observation};

const MAX_JNI_HANDLE: u64 = i64::MAX as u64;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum EventBridgeError {
    InvalidHandle,
    InvalidConfig(EventConfigError),
    Serialization,
    HandleSpaceExhausted,
}

impl fmt::Display for EventBridgeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::InvalidHandle => "event engine handle is invalid",
            Self::InvalidConfig(_) => "event engine configuration is invalid",
            Self::Serialization => "event serialization failed",
            Self::HandleSpaceExhausted => "event engine handle space is exhausted",
        })
    }
}

impl std::error::Error for EventBridgeError {}

/// Process-local registry for Android event-engine sessions.
///
/// Handles are deliberately unrelated to memory addresses. The JNI layer protects one
/// registry with a mutex; tests can instantiate independent registries directly.
pub struct EventEngineRegistry {
    next_handle: u64,
    engines: BTreeMap<u64, EventEngine>,
}

impl Default for EventEngineRegistry {
    fn default() -> Self {
        Self {
            next_handle: 1,
            engines: BTreeMap::new(),
        }
    }
}

impl EventEngineRegistry {
    pub fn create(&mut self, source_id: &str) -> Result<u64, EventBridgeError> {
        // Exhaustion is practically unreachable, but creation remains fallible rather
        // than overwriting a live engine after integer wraparound.
        let handle = self.next_available_handle()?;
        let engine = EventEngine::new(source_id, EventConfig::default())
            .map_err(EventBridgeError::InvalidConfig)?;
        self.engines.insert(handle, engine);
        self.next_handle = if handle == MAX_JNI_HANDLE {
            1
        } else {
            handle + 1
        };
        Ok(handle)
    }

    pub fn destroy(&mut self, handle: u64) -> bool {
        self.engines.remove(&handle).is_some()
    }

    pub fn process(
        &mut self,
        handle: u64,
        observation: Observation,
    ) -> Result<Vec<String>, EventBridgeError> {
        let engine = self
            .engines
            .get_mut(&handle)
            .ok_or(EventBridgeError::InvalidHandle)?;
        engine
            .process(observation)
            .into_iter()
            .map(|event| event.to_json().map_err(|_| EventBridgeError::Serialization))
            .collect()
    }

    fn next_available_handle(&self) -> Result<u64, EventBridgeError> {
        let start = self.next_handle.max(1);
        let mut candidate = start;
        loop {
            if !self.engines.contains_key(&candidate) {
                return Ok(candidate);
            }
            candidate = if candidate == MAX_JNI_HANDLE {
                1
            } else {
                candidate + 1
            };
            if candidate == start {
                return Err(EventBridgeError::HandleSpaceExhausted);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::events::{ActantSlot, Pose};

    #[test]
    fn handle_is_invalid_after_destroy() {
        let mut registry = EventEngineRegistry::default();
        let handle = registry.create("camera_back").unwrap();
        assert!(registry.destroy(handle));
        assert!(!registry.destroy(handle));
        assert_eq!(
            registry.process(handle, Observation::new(1, ActantSlot(1))),
            Err(EventBridgeError::InvalidHandle)
        );
    }

    #[test]
    fn sessions_keep_temporal_state_isolated() {
        let mut registry = EventEngineRegistry::default();
        let first = registry.create("camera_back").unwrap();
        let second = registry.create("camera_side").unwrap();

        let mut upright = Observation::new(100, ActantSlot(1));
        upright.pose = Pose::Upright;
        registry.process(first, upright).unwrap();

        let mut descent = Observation::new(200, ActantSlot(1));
        descent.pose = Pose::Horizontal;
        descent.rapid_descent_score = 1.0;
        descent.impact_score = 1.0;

        let first_events = registry.process(first, descent.clone()).unwrap();
        let second_events = registry.process(second, descent).unwrap();
        assert_eq!(first_events.len(), 1);
        assert!(first_events[0].contains("\"type\":\"fall\""));
        assert!(second_events.is_empty());
    }

    #[test]
    fn invalid_source_does_not_consume_a_handle() {
        let mut registry = EventEngineRegistry::default();
        assert!(registry.create(" ").is_err());
        assert_eq!(registry.create("camera_back").unwrap(), 1);
    }

    #[test]
    fn handles_wrap_inside_positive_jni_long_space() {
        let mut registry = EventEngineRegistry {
            next_handle: MAX_JNI_HANDLE,
            engines: BTreeMap::new(),
        };
        assert_eq!(registry.create("camera_back").unwrap(), MAX_JNI_HANDLE);
        assert_eq!(registry.create("camera_side").unwrap(), 1);
    }
}
