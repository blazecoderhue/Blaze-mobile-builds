// Blaze Player (Apache-2.0). Added by Blazeinchue.
// Thin wrapper over the native BlazeAgent module (see native-src/android/com/blaze/player).
import {NativeEventEmitter, NativeModules, Platform} from 'react-native';

const M: any = NativeModules.BlazeAgent;

export const agentNative = {
  available: Platform.OS === 'android' && !!M,
  setLibrary(json: string) {
    M?.setLibrary?.(json);
  },
  getQueue(): Promise<string> {
    return M?.getQueue ? M.getQueue() : Promise.resolve('[]');
  },
  setQueue(json: string): Promise<boolean> {
    return M?.setQueue ? M.setQueue(json) : Promise.resolve(true);
  },
  consumePendingCommand(): Promise<string | null> {
    return M?.consumePendingCommand ? M.consumePendingCommand() : Promise.resolve(null);
  },
  emitter(): NativeEventEmitter | null {
    return M ? new NativeEventEmitter(M) : null;
  },
};

export const AGENT_COMMAND_EVENT = 'BlazeAgentCommand';
export const QUEUE_CHANGED_EVENT = 'BlazeQueueChanged';
