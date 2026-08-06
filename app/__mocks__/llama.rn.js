// Jest manual mock: llama.rn is a native (JSI) module and can't load under Node.
module.exports = {
  initLlama: async () => ({
    initMultimodal: async () => {},
    completion: async () => ({text: ''}),
    release: async () => {},
  }),
};
