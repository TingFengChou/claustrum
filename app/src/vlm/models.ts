/**
 * On-device model file paths. Models are pushed to the app's external files dir
 * (adb push … /sdcard/Android/data/com.claustrum/files/models/). This is the
 * literal path Android's getExternalFilesDir(null) returns for this package;
 * hardcoded for the MVP (a production build would resolve it natively / via RNFS).
 *
 * SmolVLM-256M: a tiny vision-language model built for near-real-time on-device
 * captioning — the right fit for the live-subtitle effect (Gemma E2B/E4B is far
 * slower per frame on a phone CPU; it stays for higher-quality Kineme captions).
 */
const DIR = '/storage/emulated/0/Android/data/com.claustrum/files/models';

export const SMOLVLM_256M = {
  model: `${DIR}/SmolVLM-256M-Instruct-Q8_0.gguf`,
  mmproj: `${DIR}/mmproj-SmolVLM-256M-Instruct-Q8_0.gguf`,
} as const;
