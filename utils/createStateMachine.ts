type State = string;
type Event = string;
type Transition = (state: State, event: Event) => State;

export interface StateMachine {
  initialState: State;
  transition: Transition;
}

export function createStateMachine(
  states: State[],
  events: Event[],
  transitions: Record<State, Partial<Record<Event, State>>>
): StateMachine {
  return {
    initialState: states[0],
    transition: (state: State, event: Event) => {
      const nextState = transitions[state]?.[event];
      return nextState || state;
    },
  };
}

// 物理システムの簡単なステートマシンを定義
export const physicsStateMachine = createStateMachine(
  ['stable', 'excited', 'decaying'],
  ['excite', 'decay', 'stabilize'],
  {
    stable: { excite: 'excited' },
    excited: { decay: 'decaying' },
    decaying: { stabilize: 'stable' },
  }
);

