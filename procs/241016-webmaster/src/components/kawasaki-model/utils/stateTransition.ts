export interface TransitionState {
  currentState: string
  targetState: string | null
  progress: number // 0から1の間の値（0: 開始、1: 完了）
  transitionDuration?: number
}

export function getTransitionProgress(state: TransitionState, deltaTime: number, transitionSpeed = 1): TransitionState {
  // 遷移中でない場合はそのまま返す
  if (state.targetState === null || state.progress >= 1) {
    return state
  }

  // 進行度を更新（transitionSpeedは1秒あたりの遷移速度）
  const newProgress = Math.min(state.progress + (deltaTime / 1000) * transitionSpeed, 1)

  // 遷移が完了した場合
  if (newProgress >= 1) {
    return {
      currentState: state.targetState,
      targetState: null,
      progress: 0,
    }
  }

  // 遷移中
  return {
    ...state,
    progress: newProgress,
  }
}

// 新しい遷移を開始する
export function startTransition(currentState: string, targetState: string): TransitionState {
  return {
    currentState,
    targetState,
    progress: 0,
  }
}

