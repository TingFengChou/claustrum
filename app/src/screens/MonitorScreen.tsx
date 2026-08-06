import React, {useEffect, useRef, useState} from 'react';
import {Pressable, StatusBar, StyleSheet, Text, View} from 'react-native';
import {
  Camera,
  type CameraRef,
  useCameraDevice,
  useCameraPermission,
  useMicrophonePermission,
  usePhotoOutput,
} from 'react-native-vision-camera';
import {describeImage, loadVlm, releaseVlm} from '../vlm/vlmService';
import {SMOLVLM_256M} from '../vlm/models';
import {CAPTION_PROMPT} from '../vlm/caption';
import {colors, font, radius, space} from '../theme';

/**
 * MVP A — 感知閉環(perception loop). Live on-device camera preview with a
 * monitoring status and an alert channel. Detection (pose/fall, audio/violence)
 * plugs in next (A-2 / B); the "模擬偵測" control below stands in for the
 * detector so the perceive → detect → alert path is real end to end.
 */
export default function MonitorScreen({onBack}: {onBack: () => void}): React.JSX.Element {
  const {hasPermission: hasCamera, requestPermission: requestCamera} = useCameraPermission();
  // Mic permission is requested as groundwork for on-device audio detection
  // (violence-sound, ADR-0006). Audio capture is wired with detection in B/C.
  const {requestPermission: requestMic} = useMicrophonePermission();
  const device = useCameraDevice('back');
  const photoOutput = usePhotoOutput({qualityPrioritization: 'speed'});
  const camera = useRef<CameraRef>(null);
  const [alerting, setAlerting] = useState(false);
  const [caption, setCaption] = useState('');
  const [vlmStatus, setVlmStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Request on explicit user action (below), not abruptly on mount.
  useEffect(() => {
    return () => {
      if (timer.current) {
        clearTimeout(timer.current);
      }
    };
  }, []);

  // Load the on-device VLM (SmolVLM) once the camera is available.
  useEffect(() => {
    if (!hasCamera || !device) {
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        await loadVlm(SMOLVLM_256M);
        if (!cancelled) {
          setVlmStatus('ready');
        }
      } catch {
        if (!cancelled) {
          setVlmStatus('error');
        }
      }
    })();
    return () => {
      cancelled = true;
      releaseVlm();
    };
  }, [hasCamera, device]);

  // Live-caption loop (即時字幕): snapshot → on-device VLM → subtitle.
  useEffect(() => {
    if (vlmStatus !== 'ready') {
      return;
    }
    let active = true;
    (async () => {
      while (active) {
        try {
          const file = await photoOutput.capturePhotoToFile({}, {});
          const text = await describeImage(file.filePath, CAPTION_PROMPT, 48);
          if (active && text.trim()) {
            setCaption(text.trim());
          }
          // TODO(cleanup): capturePhotoToFile writes a temp file per frame;
          // delete it after use once a native fs helper is available.
        } catch {
          // skip this frame
        }
        await new Promise<void>(res => setTimeout(res, 400));
      }
    })();
    return () => {
      active = false;
    };
  }, [vlmStatus]);

  const requestAccess = async () => {
    await requestCamera();
    await requestMic(); // audio modality — needed for violence-sound detection (ADR-0006)
  };

  const fireAlert = () => {
    setAlerting(true);
    if (timer.current) {
      clearTimeout(timer.current);
    }
    timer.current = setTimeout(() => setAlerting(false), 4000);
  };

  if (!hasCamera) {
    return (
      <View style={styles.center}>
        <StatusBar barStyle="light-content" />
        <Text style={styles.centerTitle}>需要相機與麥克風權限</Text>
        <Text style={styles.centerBody}>主動防護需要即時看到畫面、聽到聲音。影像與聲音只在此裝置上處理,不上傳。</Text>
        <Pressable style={styles.btn} onPress={requestAccess}>
          <Text style={styles.btnText}>允許相機與麥克風</Text>
        </Pressable>
        <Pressable style={styles.linkBtn} onPress={onBack}>
          <Text style={styles.linkText}>返回</Text>
        </Pressable>
      </View>
    );
  }

  if (!device) {
    return (
      <View style={styles.center}>
        <StatusBar barStyle="light-content" />
        <Text style={styles.centerTitle}>找不到相機</Text>
        <Pressable style={styles.linkBtn} onPress={onBack}>
          <Text style={styles.linkText}>返回</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
      <Camera
        ref={camera}
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={true}
        outputs={[photoOutput]}
      />

      {/* top status */}
      <View style={styles.topBar}>
        <View style={styles.statusPill}>
          <View style={styles.liveDot} />
          <Text style={styles.statusText}>監測中 · Edge AI</Text>
        </View>
        <Pressable onPress={onBack} hitSlop={12}>
          <Text style={styles.back}>返回</Text>
        </Pressable>
      </View>

      {/* alert banner */}
      {alerting && (
        <View style={styles.alert}>
          <Text style={styles.alertTitle}>⚠️ 偵測到事件</Text>
          <Text style={styles.alertBody}>已在裝置端記錄並發出告警(示範)</Text>
        </View>
      )}

      {/* live caption (即時字幕) — on-device VLM narrates the scene */}
      <View style={styles.bottom}>
        <View style={styles.captionBar}>
          <Text style={styles.captionLabel}>即時描述 · 裝置端 SmolVLM</Text>
          <Text style={styles.captionText} numberOfLines={3}>
            {vlmStatus === 'loading'
              ? '載入裝置端模型中…'
              : vlmStatus === 'error'
              ? '模型載入失敗(請確認模型檔已推送到裝置)'
              : caption || '感知中…'}
          </Text>
        </View>
        <Pressable style={styles.simBtn} onPress={fireAlert}>
          <Text style={styles.simText}>模擬偵測事件</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: colors.bgDeep},
  center: {
    flex: 1,
    backgroundColor: colors.bg,
    alignItems: 'center',
    justifyContent: 'center',
    padding: space(8),
  },
  centerTitle: {color: colors.text, fontSize: font.title, fontWeight: '700', marginBottom: space(3)},
  centerBody: {color: colors.textDim, fontSize: font.body, textAlign: 'center', lineHeight: 22, marginBottom: space(6)},
  btn: {
    backgroundColor: colors.surfaceHi,
    borderWidth: 1,
    borderColor: colors.borderHi,
    borderRadius: radius.md,
    paddingVertical: space(3.5),
    paddingHorizontal: space(8),
  },
  btnText: {color: colors.text, fontSize: font.body, fontWeight: '600'},
  linkBtn: {marginTop: space(4)},
  linkText: {color: colors.textFaint, fontSize: font.label},

  topBar: {
    position: 'absolute',
    top: (StatusBar.currentHeight ?? 0) + space(3),
    left: space(4),
    right: space(4),
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(15,12,28,0.72)',
    borderRadius: radius.pill,
    paddingVertical: space(2),
    paddingHorizontal: space(3.5),
  },
  liveDot: {width: 8, height: 8, borderRadius: radius.pill, backgroundColor: colors.language, marginRight: space(2)},
  statusText: {color: colors.text, fontSize: font.label},
  back: {
    color: colors.text,
    fontSize: font.label,
    backgroundColor: 'rgba(15,12,28,0.72)',
    borderRadius: radius.pill,
    paddingVertical: space(2),
    paddingHorizontal: space(3.5),
    overflow: 'hidden',
  },

  alert: {
    position: 'absolute',
    top: (StatusBar.currentHeight ?? 0) + space(14),
    left: space(4),
    right: space(4),
    backgroundColor: 'rgba(255,92,138,0.94)',
    borderRadius: radius.md,
    padding: space(4),
  },
  alertTitle: {color: '#2a0713', fontSize: font.body, fontWeight: '800'},
  alertBody: {color: '#3a0a1c', fontSize: font.label, marginTop: space(1)},

  bottom: {
    position: 'absolute',
    bottom: space(8),
    left: space(5),
    right: space(5),
    alignItems: 'center',
  },
  captionBar: {
    alignSelf: 'stretch',
    backgroundColor: 'rgba(15,12,28,0.78)',
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    paddingVertical: space(3),
    paddingHorizontal: space(4),
    marginBottom: space(3),
  },
  captionLabel: {
    color: colors.language,
    fontSize: font.micro,
    letterSpacing: 1,
    marginBottom: space(1.5),
  },
  captionText: {
    color: colors.text,
    fontSize: font.body,
    lineHeight: 22,
  },
  simBtn: {
    backgroundColor: 'rgba(31,25,64,0.9)',
    borderWidth: 1,
    borderColor: colors.borderHi,
    borderRadius: radius.md,
    paddingVertical: space(3.5),
    paddingHorizontal: space(10),
  },
  simText: {color: colors.text, fontSize: font.body, fontWeight: '600'},
});
