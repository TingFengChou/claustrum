import React from 'react';
import {
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {colors, font, radius, space} from '../theme';

/** The three modality accents, echoing the app icon's strands. */
function AccentDots(): React.JSX.Element {
  return (
    <View style={styles.dots}>
      <View style={[styles.dot, {backgroundColor: colors.vision}]} />
      <View style={[styles.dot, {backgroundColor: colors.audio}]} />
      <View style={[styles.dot, {backgroundColor: colors.language}]} />
    </View>
  );
}

function StatusPill({label}: {label: string}): React.JSX.Element {
  return (
    <View style={styles.pill}>
      <View style={styles.pillDot} />
      <Text style={styles.pillText}>{label}</Text>
    </View>
  );
}

export default function HomeScreen(): React.JSX.Element {
  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
      <ScrollView
        contentContainerStyle={styles.content}
        showsVerticalScrollIndicator={false}>
        <View style={styles.header}>
          <Text style={styles.wordmark}>claustrum</Text>
          <StatusPill label="感測未啟動" />
        </View>

        <View style={styles.hero}>
          <Text style={styles.heroLabel}>今日家中</Text>
          <Text style={styles.heroTitle}>感測尚未啟動</Text>
          <Text style={styles.heroBody}>
            啟動後,這裡會即時彙整家中的觀察事件 —— 一條可查詢、附時間的事件串流。
          </Text>
          <View style={styles.modalityRow}>
            <AccentDots />
            <Text style={styles.modalityText}>視覺 · 音訊 · 語言</Text>
          </View>
        </View>

        <Text style={styles.sectionTitle}>最近事件</Text>
        <View style={styles.emptyCard}>
          <Text style={styles.emptyTitle}>尚無事件</Text>
          <Text style={styles.emptyBody}>
            感測啟動後,觀察到的行為會以 Kineme 形式即時出現在這裡。
          </Text>
        </View>

        <Pressable
          style={({pressed}) => [styles.cta, pressed && styles.ctaPressed]}
          accessibilityRole="button">
          <Text style={styles.ctaText}>詢問家中狀況</Text>
        </Pressable>

        <Text style={styles.footer}>影格僅留存於此裝置 · Edge AI · 離線優先</Text>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: colors.bg},
  content: {
    paddingTop: (StatusBar.currentHeight ?? 0) + space(4),
    paddingHorizontal: space(5),
    paddingBottom: space(10),
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: space(7),
  },
  wordmark: {
    color: colors.text,
    fontSize: font.title,
    fontWeight: '600',
    letterSpacing: 0.5,
  },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: space(1.5),
    paddingHorizontal: space(3),
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
  },
  pillDot: {
    width: 7,
    height: 7,
    borderRadius: radius.pill,
    backgroundColor: colors.textFaint,
    marginRight: space(2),
  },
  pillText: {color: colors.textDim, fontSize: font.micro},

  hero: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    padding: space(6),
    marginBottom: space(8),
  },
  heroLabel: {
    color: colors.textFaint,
    fontSize: font.micro,
    letterSpacing: 1.5,
    textTransform: 'uppercase',
    marginBottom: space(3),
  },
  heroTitle: {
    color: colors.text,
    fontSize: font.hero,
    fontWeight: '700',
    marginBottom: space(3),
  },
  heroBody: {color: colors.textDim, fontSize: font.body, lineHeight: 22},
  modalityRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: space(6),
  },
  dots: {flexDirection: 'row', marginRight: space(3)},
  dot: {width: 9, height: 9, borderRadius: radius.pill, marginRight: space(1.5)},
  modalityText: {color: colors.textFaint, fontSize: font.label, letterSpacing: 0.5},

  sectionTitle: {
    color: colors.text,
    fontSize: font.title,
    fontWeight: '600',
    marginBottom: space(3),
  },
  emptyCard: {
    backgroundColor: colors.bgDeep,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    borderStyle: 'dashed',
    padding: space(6),
    marginBottom: space(8),
  },
  emptyTitle: {
    color: colors.textDim,
    fontSize: font.body,
    fontWeight: '600',
    marginBottom: space(2),
  },
  emptyBody: {color: colors.textFaint, fontSize: font.label, lineHeight: 20},

  cta: {
    backgroundColor: colors.surfaceHi,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.borderHi,
    paddingVertical: space(4),
    alignItems: 'center',
    marginBottom: space(6),
  },
  ctaPressed: {opacity: 0.7},
  ctaText: {color: colors.text, fontSize: font.body, fontWeight: '600'},

  footer: {
    color: colors.textFaint,
    fontSize: font.micro,
    textAlign: 'center',
    letterSpacing: 0.5,
  },
});
