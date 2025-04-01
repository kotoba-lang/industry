import { physicsStateMachine } from './createStateMachine';

export function getNextState(currentState: string): string {
  const randomEvent = Math.random();
  
  switch (currentState) {
    case 'stable':
      return randomEvent < 0.1 ? physicsStateMachine.transition(currentState, 'excite') : currentState;
    case 'excited':
      return randomEvent < 0.3 ? physicsStateMachine.transition(currentState, 'decay') : currentState;
    case 'decaying':
      return randomEvent < 0.5 ? physicsStateMachine.transition(currentState, 'stabilize') : currentState;
    default:
      return currentState;
  }
}

