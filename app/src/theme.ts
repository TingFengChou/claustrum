/**
 * Visual identity for the claustrum app, derived from the app icon
 * (assets/icon/claustrum.svg): a deep indigo ground with three modality
 * accents — vision, audio, language — that fuse into one stream.
 */

export const colors = {
  bg: '#0f0c1c',
  bgDeep: '#070610',
  surface: '#171326',
  surfaceHi: '#1f1940',
  border: 'rgba(174,184,255,0.14)',
  borderHi: 'rgba(174,184,255,0.28)',

  text: '#f4f6ff',
  textDim: 'rgba(207,232,255,0.68)',
  textFaint: 'rgba(174,184,255,0.45)',

  // modality accents (match the icon strands)
  vision: '#ffb054',
  audio: '#ff5c8a',
  language: '#43e0d0',

  accent: '#8be9ff',
  white: '#ffffff',
} as const;

/** 4pt spacing scale. */
export const space = (n: number): number => n * 4;

export const radius = {
  sm: 10,
  md: 16,
  lg: 24,
  pill: 999,
} as const;

export const font = {
  hero: 30,
  title: 20,
  body: 15,
  label: 13,
  micro: 11,
} as const;
