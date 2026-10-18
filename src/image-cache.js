// Persistent image copies only. Business records and original files live on the server.
export const IMAGE_CACHE_LIMIT = 1024 ** 3;
const DATABASE = 'fire-assistant-image-cache';
let opening;
let clock = 0;
const stamp = (minimum = 0) => clock = Math.max(Date.now(), clock + 1, minimum + 1);

function database() {
  if (!globalThis.indexedDB) return Promise.resolve(null);
  if (!opening) opening = new Promise(resolve => {
    let request;
    try { request = indexedDB.open(DATABASE, 1); } catch { resolve(null); return; }
    request.onupgradeneeded = () => {
      const db = request.result;
      const entries = db.createObjectStore('entries', {keyPath: 'key'});
      entries.createIndex('scope', 'scope');
      entries.createIndex('lastUsed', 'lastUsed');
      db.createObjectStore('images');
      db.createObjectStore('epochs');
    };
    request.onerror = request.onblocked = () => resolve(null);
    request.onsuccess = () => {
      const db = request.result;
      db.onversionchange = () => { db.close(); opening = undefined; };
      resolve(db);
    };
  });
  return opening;
}

async function transaction(mode, work) {
  const db = await database();
  if (!db) return null;
  return new Promise((resolve, reject) => {
    let value;
    const tx = db.transaction(['entries', 'images', 'epochs'], mode);
    tx.oncomplete = () => resolve(value);
    tx.onerror = tx.onabort = () => reject(tx.error || new Error('Image cache unavailable'));
    work(tx.objectStore('entries'), tx.objectStore('images'), result => { value = result; }, tx.objectStore('epochs'));
  });
}

export async function cachedImage(scope, id, taskId) {
  try {
    return await transaction('readwrite', (entries, images, done) => {
      const key = [scope, id];
      const lookup = entries.get(key);
      lookup.onsuccess = () => {
        const entry = lookup.result;
        if (!entry || entry.taskId !== taskId) return;
        const image = images.get(key);
        image.onsuccess = () => {
          if (!(image.result instanceof Blob)) { entries.delete(key); return; }
          entries.put({...entry, lastUsed: stamp(entry.lastUsed)});
          done(image.result);
        };
      };
    });
  } catch { return null; }
}

async function put(scope, id, taskId, blob, valid, epoch) {
  return transaction('readwrite', (entries, images, done, epochs) => {
    const generation = epochs.get(scope);
    generation.onsuccess = () => {
      if ((generation.result || 0) !== epoch || !valid()) return;
      const lookup = entries.index('lastUsed').getAll();
      lookup.onsuccess = () => {
        if (!valid()) return;
        const key = [scope, id];
        const others = lookup.result.filter(entry => entry.scope !== scope || entry.id !== id);
        let bytes = others.reduce((sum, entry) => sum + entry.size, 0);
        for (const entry of others) {
          if (bytes + blob.size <= IMAGE_CACHE_LIMIT) break;
          entries.delete(entry.key); images.delete(entry.key); bytes -= entry.size;
        }
        entries.put({key, scope, id, taskId, size: blob.size, lastUsed: stamp(lookup.result.at(-1)?.lastUsed || 0)});
        images.put(blob, key);
        done(true);
      };
    };
  });
}

export async function imageCacheEpoch(scope) {
  try {
    return await transaction('readonly', (entries, images, done, epochs) => {
      const lookup = epochs.get(scope);
      lookup.onsuccess = () => done(lookup.result || 0);
    }) || 0;
  } catch { return 0; }
}

export async function storeImage(scope, id, taskId, blob, valid = () => true, epoch = 0) {
  if (!blob.type.startsWith('image/') || blob.size > IMAGE_CACHE_LIMIT || !valid()) return false;
  try { return !!await put(scope, id, taskId, blob, valid, epoch); }
  catch (error) {
    if (error.name !== 'QuotaExceededError' || !valid()) return false;
    // Devices may grant less than 1 GiB. Free older copies and retry once.
    try {
      await transaction('readwrite', (entries, images) => {
        const lookup = entries.index('lastUsed').openCursor();
        let bytes = 0;
        lookup.onsuccess = () => {
          const cursor = lookup.result;
          if (!cursor || bytes >= Math.max(blob.size * 2, 32 * 1024 ** 2) || !valid()) return;
          bytes += cursor.value.size;
          images.delete(cursor.value.key); cursor.delete(); cursor.continue();
        };
      });
      return valid() && !!await put(scope, id, taskId, blob, valid, epoch);
    } catch { return false; }
  }
}

export async function removeCachedImages(scope, allowedTasks = null) {
  if (!scope) return;
  try {
    await transaction('readwrite', (entries, images, done, epochs) => {
      if (!allowedTasks) {
        const generation = epochs.get(scope);
        generation.onsuccess = () => epochs.put((generation.result || 0) + 1, scope);
      }
      const lookup = entries.index('scope').openCursor(IDBKeyRange.only(scope));
      lookup.onsuccess = () => {
        const cursor = lookup.result;
        if (!cursor) return;
        if (!allowedTasks || !allowedTasks.has(cursor.value.taskId)) {
          images.delete(cursor.value.key); cursor.delete();
        }
        cursor.continue();
      };
    });
    return true;
  } catch { return false; }
}

export async function cachedImageBytes(scope) {
  if (!scope) return 0;
  try {
    return await transaction('readonly', (entries, images, done) => {
      const lookup = entries.index('scope').getAll(IDBKeyRange.only(scope));
      lookup.onsuccess = () => done(lookup.result.reduce((sum, entry) => sum + entry.size, 0));
    }) || 0;
  } catch { return 0; }
}
