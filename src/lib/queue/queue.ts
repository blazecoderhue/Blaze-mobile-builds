// Blaze Player (Apache-2.0). Added by Blazeinchue.
// Play queue. Native (AgentQueue.kt) is the source of truth so the agent API can read it while
// the JS runtime is asleep; this store mirrors it.
import {create} from 'zustand';
import {MediaItem} from '../sources/types';
import {agentNative, QUEUE_CHANGED_EVENT} from '../agent/native';

export const QUEUE_MAX = 200;

/** Pure queue operations, shared semantics with AgentQueue.kt. */
export const queueOps = {
  add(queue: MediaItem[], items: MediaItem[]): MediaItem[] {
    return [...queue, ...items].slice(0, QUEUE_MAX);
  },
  remove(queue: MediaItem[], index: number): MediaItem[] {
    return queue.filter((_, i) => i !== index);
  },
  clear(): MediaItem[] {
    return [];
  },
};

interface QueueState {
  items: MediaItem[];
  hydrate: () => Promise<void>;
  add: (items: MediaItem[]) => void;
  removeAt: (index: number) => void;
  clear: () => void;
  shift: () => MediaItem | undefined;
}

function save(items: MediaItem[]) {
  agentNative.setQueue(JSON.stringify(items)).catch(() => {});
}

export const useQueue = create<QueueState>()((set, get) => ({
  items: [],
  hydrate: async () => {
    try {
      const parsed = JSON.parse((await agentNative.getQueue()) || '[]');
      set({items: Array.isArray(parsed) ? parsed : []});
    } catch {
      set({items: []});
    }
  },
  add: items => {
    const next = queueOps.add(get().items, items);
    set({items: next});
    save(next);
  },
  removeAt: index => {
    const next = queueOps.remove(get().items, index);
    set({items: next});
    save(next);
  },
  clear: () => {
    set({items: []});
    save([]);
  },
  shift: () => {
    const [first, ...rest] = get().items;
    set({items: rest});
    save(rest);
    return first;
  },
}));

export function listenForNativeQueueChanges(): () => void {
  const em = agentNative.emitter();
  const sub = em?.addListener(QUEUE_CHANGED_EVENT, () => {
    useQueue.getState().hydrate();
  });
  return () => sub?.remove();
}
