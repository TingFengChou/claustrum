// Jest manual mock: vision-camera is a native module and can't load under Node.
// Auto-used by Jest for this node module (file lives in <rootDir>/__mocks__).
module.exports = {
  Camera: () => null,
  useCameraDevice: () => null,
  useCameraPermission: () => ({hasPermission: false, requestPermission: () => {}}),
};
