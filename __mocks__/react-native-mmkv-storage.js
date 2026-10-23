// Blaze Player (Apache-2.0). Added by Blazeinchue. In-memory MMKV for jest.
class MMKVLoader {
  withInstanceID() {
    return this;
  }
  withEncryption() {
    return this;
  }
  initialize() {
    const m = new Map();
    return {
      getString: k => m.get(k),
      setString: (k, v) => m.set(k, v),
      getBool: k => m.get(k),
      setBool: (k, v) => m.set(k, v),
      getInt: k => m.get(k),
      setInt: (k, v) => m.set(k, v),
      getArray: k => m.get(k),
      setArray: (k, v) => m.set(k, v),
      getMap: k => m.get(k),
      setMap: (k, v) => m.set(k, v),
      removeItem: k => m.delete(k),
      clearStore: () => m.clear(),
      getItem: k => m.get(k) ?? null,
      setItem: (k, v) => m.set(k, v),
    };
  }
}
module.exports = {MMKVLoader};
