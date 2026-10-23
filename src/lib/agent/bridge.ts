// Blaze Player (Apache-2.0). Added by Blazeinchue.
// Receives open/play/cast commands from the native agent interface and drives the UI.
import {AGENT_COMMAND_EVENT, agentNative} from './native';
import {AgentCommand, parseAgentCommand} from './commands';
import {castResolved, openScreen, playItem} from '../playback';

export async function runAgentCommand(cmd: AgentCommand): Promise<void> {
  const target = cmd.sourceId ?? cmd.uri;
  switch (cmd.action) {
    case 'open':
      if (target) {
        await playItem(target);
      } else {
        openScreen(cmd.screen);
      }
      return;
    case 'play':
      await playItem(target!);
      return;
    case 'cast':
      await castResolved(target!);
      return;
  }
}

export function startAgentBridge(onError?: (e: unknown) => void): () => void {
  const handle = (raw: string | null) => {
    const cmd = parseAgentCommand(raw);
    if (cmd) {
      runAgentCommand(cmd).catch(e => onError?.(e));
    }
  };
  // The native event is only a signal; the command is taken atomically so it runs exactly once.
  const take = () => agentNative.consumePendingCommand().then(handle).catch(() => {});
  take();
  const sub = agentNative.emitter()?.addListener(AGENT_COMMAND_EVENT, take);
  return () => sub?.remove();
}
