// Blaze Player (Apache-2.0). Added by Blazeinchue.
// Commands delivered by the native agent interface (AgentActivity / AgentService) to the UI.
export type AgentAction = 'open' | 'play' | 'cast';

export interface AgentCommand {
  action: AgentAction;
  /** content:// or https://archive.org/... */
  uri?: string;
  /** archive:<identifier> or local:<uri> */
  sourceId?: string;
  title?: string;
  /** For open: home | library | search | queue | settings */
  screen?: string;
  query?: string;
}

const SCREENS = ['home', 'library', 'search', 'queue', 'settings'];

export function parseAgentCommand(raw: string | null | undefined): AgentCommand | null {
  if (!raw) {
    return null;
  }
  let o: any;
  try {
    o = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!o || !['open', 'play', 'cast'].includes(o.action)) {
    return null;
  }
  const str = (v: any, max = 2048) => (typeof v === 'string' && v.length <= max ? v : undefined);
  const cmd: AgentCommand = {action: o.action};
  cmd.uri = str(o.uri);
  cmd.sourceId = str(o.sourceId, 1024);
  cmd.title = str(o.title, 300);
  cmd.query = str(o.query, 200);
  const screen = str(o.screen, 20);
  cmd.screen = screen && SCREENS.includes(screen) ? screen : undefined;
  if ((cmd.action === 'play' || cmd.action === 'cast') && !cmd.uri && !cmd.sourceId) {
    return null;
  }
  return cmd;
}
