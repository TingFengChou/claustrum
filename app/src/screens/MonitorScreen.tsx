import React, {useEffect, useRef, useState} from 'react';
import {Pressable, StatusBar, StyleSheet, Text, View} from 'react-native';
import {
  Camera,
  useCameraDevice,
  useCameraPermission,
} from 'react-native-vision-camera';
import {colors, font, radius, space} from '../theme';

/**
 * MVP A — 感知閉環(perception loop). Live on-device camera preview with a
 * monitoring status and an alert channel. Detection (pose/fall, audio/violence)
 * plugs in next (A-2 / B); the "模擬偵測" control below stands in for the
 * detector so the perceive → detect → alert path is real end to end.
 */
export default function MonitorScreen({onBack}: {onBack: () => void}): React.JSX.Element {
  const {hasPermission, requestPermission} = useCameraPermission();
  const device = useCameraDevice('back');
  const [alerting, setAlerting] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!hasPermission) {
      requestPermission();
    }
    return () => {
      if (timer.current) {
        clearTimeout(timer.current);
      }
    };
  }, [hasPermission, requestPermission]);

  const fireAlert = () => {
    setAlerting(true);
    if (timer.current) {
      clearTimeout(timer.current);
    }
    timer.current = setTimeout(() => setAlerting(false), 4000);
  };

  if (!hasPermission) {
    return (
      <View style={styles.center}>
        <StatusBar barStyle="light-content" />
        <Text style={styles.centerTitle}>需要相機權限</Text>
        <Text style={styles.centerBody}>主動防護需要即時看到畫面。影像只在此裝置上處理,不上傳。</Text>
        <Pressable style={styles.btn} onPress={requestPermission}>
          <Text style={styles.btnText}>允許相機</Text>
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
      <Camera style={StyleSheet.absoluteFill} device={device} isActive={true} />

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

      {/* bottom controls — 模擬偵測 is a stand-in until on-device detection (B) lands */}
      <View style={styles.bottom}>
        <Text style={styles.hint}>感知閉環已運行。偵測模型(pose / 音訊)接上後,事件會自動觸發告警。</Text>
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
  hint: {
    color: colors.textDim,
    fontSize: font.micro,
    textAlign: 'center',
    marginBottom: space(3),
    backgroundColor: 'rgba(15,12,28,0.6)',
    borderRadius: radius.sm,
    paddingVertical: space(2),
    paddingHorizontal: space(3),
    overflow: 'hidden',
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
