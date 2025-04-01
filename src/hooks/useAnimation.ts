"use client"

import { useRef, useEffect, useState, useCallback } from "react"
import { type TransitionState, startTransition } from "../utils/stateTransition"
import { physicsStateMachine } from "../utils/createStateMachine"

interface AnimationOptions {
  initialSpeed?: number
  initialPlaying?: boolean
  autoTransitionProbability?: {
    stable: number
    excited: number
    decaying: number
  }
}

export function useAnimation(options: AnimationOptions = {}) {
  const {
    initialSpeed = 1,
    initialPlaying = false,
    autoTransitionProbability = {
      stable: 0.1,
      excited: 0.3,
      decaying: 0.5,
    },
  } = options

  // 状態
  const [transitionState, setTransitionState] = useState<TransitionState>({
    currentState: "stable",
    targetState: null,
    progress: 0,
  })
  const [time, setTime] = useState(0)
  const [isPlaying, setIsPlaying] = useState(initialPlaying)
  const [speed, setSpeed] = useState(initialSpeed)

  // 時間の累積を追跡するためのref
  const timeAccumulatorRef = useRef(0)
  // 状態遷移の累積を追跡するためのref
  const stateTransitionAccumulatorRef = useRef(0)
  // 前回の時間更新からの経過時間
  const lastUpdateTimeRef = useRef(Date.now())

  // 状態遷移ハンドラー
  const handleStateChange = useCallback((action: string) => {
    setTransitionState((prevState) => {
      // 既に遷移中の場合は何もしない
      if (prevState.targetState !== null) {
        return prevState
      }

      // 次の状態を計算
      const nextState = physicsStateMachine.transition(prevState.currentState, action)

      // 状態が変わらない場合は何もしない
      if (nextState === prevState.currentState) {
        return prevState
      }

      // 新しい遷移を開始
      return startTransition(prevState.currentState, nextState)
    })
  }, [])

  // 再生/一時停止の切り替え
  const togglePlay = useCallback(() => {
    setIsPlaying((prev) => !prev)
  }, [])

  // 速度の変更
  const handleSpeedChange = useCallback((newSpeed: number) => {
    setSpeed(newSpeed)
  }, [])

  // アニメーションフレーム更新
  useEffect(() => {
    let animationFrameId: number

    const updateFrame = () => {
      // 現在時刻を取得
      const currentTime = Date.now()
      // 前回のフレームからの経過時間（秒）
      const deltaTimeSeconds = (currentTime - lastUpdateTimeRef.current) / 1000

      // 状態遷移の進行を更新
      updateTransitionState(deltaTimeSeconds)

      // 時間の累積を更新
      if (isPlaying) {
        updateTimeAccumulation(deltaTimeSeconds)
      }

      // 時間を更新
      lastUpdateTimeRef.current = currentTime

      // 次のフレームをリクエスト
      animationFrameId = requestAnimationFrame(updateFrame)
    }

    // 状態遷移の進行を更新する関数
    const updateTransitionState = (deltaTime: number) => {
      setTransitionState((prevState) => {
        // 遷移中でない場合は何もしない
        if (prevState.targetState === null) {
          return prevState
        }

        // 経過時間に基づいて進行度を更新
        const newProgress = Math.min(prevState.progress + deltaTime, 1)

        // 遷移が完了した場合
        if (newProgress >= 1) {
          return {
            currentState: prevState.targetState,
            targetState: null,
            progress: 0,
          }
        }

        // 遷移中
        return {
          ...prevState,
          progress: newProgress,
        }
      })
    }

    // 時間の累積を更新する関数
    const updateTimeAccumulation = (deltaTime: number) => {
      // 経過時間にスピード倍率を適用
      timeAccumulatorRef.current += deltaTime * speed

      // 0.01秒以上の時間が経過したら時間を更新
      if (timeAccumulatorRef.current >= 0.01) {
        // 0.01秒ごとに更新（より滑らか）
        const timeIncrement = timeAccumulatorRef.current
        setTime((prevTime) => prevTime + timeIncrement)
        timeAccumulatorRef.current = 0

        // 状態遷移の累積も更新
        stateTransitionAccumulatorRef.current += timeIncrement

        // 自動状態遷移（遷移中でない場合のみ）
        checkAutoTransition()
      }
    }

    // 自動状態遷移をチェックする関数
    const checkAutoTransition = () => {
      if (transitionState.targetState === null && stateTransitionAccumulatorRef.current >= 1) {
        stateTransitionAccumulatorRef.current = 0

        const randomEvent = Math.random()
        const currentState = transitionState.currentState

        const shouldTransition =
          (currentState === "stable" && randomEvent < autoTransitionProbability.stable) ||
          (currentState === "excited" && randomEvent < autoTransitionProbability.excited) ||
          (currentState === "decaying" && randomEvent < autoTransitionProbability.decaying)

        if (shouldTransition) {
          // 適切なアクションを選択
          let action = ""
          if (currentState === "stable") action = "excite"
          else if (currentState === "excited") action = "decay"
          else if (currentState === "decaying") action = "stabilize"

          // 状態遷移を開始
          handleStateChange(action)
        }
      }
    }

    // アニメーションを開始
    animationFrameId = requestAnimationFrame(updateFrame)

    return () => {
      if (animationFrameId) {
        cancelAnimationFrame(animationFrameId)
      }
    }
  }, [isPlaying, speed, transitionState, handleStateChange, autoTransitionProbability])

  // 時間をリセット
  const resetTime = useCallback(() => {
    setTime(0)
    timeAccumulatorRef.current = 0
    stateTransitionAccumulatorRef.current = 0
  }, [])

  return {
    transitionState,
    time,
    isPlaying,
    speed,
    handleStateChange,
    togglePlay,
    handleSpeedChange,
    resetTime,
  }
}

