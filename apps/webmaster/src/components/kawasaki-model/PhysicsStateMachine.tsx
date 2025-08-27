"use client"

import type React from "react"
import type { TransitionState } from "./utils/stateTransition"

interface PhysicsStateMachineProps {
  transitionState: TransitionState
  onStateChange: (action: string) => void
}

const PhysicsStateMachine: React.FC<PhysicsStateMachineProps> = ({ transitionState, onStateChange }) => {
  const { currentState, targetState, progress } = transitionState

  // State display names using Jungian psychology terms
  const getStateName = (state: string) => {
    switch (state) {
      case "stable":
        return "Stimulus Words"
      case "excited":
        return "Response Categories"
      case "decaying":
        return "Complex Indicators"
      default:
        return state
    }
  }

  // Transition display text
  const stateText = targetState
    ? `${getStateName(currentState)} → ${getStateName(targetState)} (${Math.round(progress * 100)}%)`
    : getStateName(currentState)

  // Get color based on state
  const getStateColor = (state: string) => {
    switch (state) {
      case "stable":
        return "bg-gray-800"
      case "excited":
        return "bg-gray-700"
      case "decaying":
        return "bg-gray-600"
      default:
        return "bg-gray-800"
    }
  }

  return (
    <div className="flex flex-col items-center space-y-2 bg-white/80 backdrop-blur-sm p-3 rounded-md shadow-sm border border-gray-200 w-full mb-2">
      <h2 className="text-sm font-bold text-gray-800">Association State: {stateText}</h2>
      {targetState && (
        <div className="w-64 h-1 bg-gray-200 rounded-full overflow-hidden">
          <div
            className={`h-full ${getStateColor(targetState)} transition-all duration-100 ease-linear`}
            style={{ width: `${progress * 100}%` }}
          ></div>
        </div>
      )}
      <div className="flex space-x-3">
        <button
          className={`px-3 py-1 text-xs rounded-full transition-colors ${
            currentState === "excited" || targetState !== null
              ? "bg-gray-300 text-gray-600 cursor-not-allowed"
              : "bg-gray-700 text-white hover:bg-gray-800"
          }`}
          onClick={() => onStateChange("excite")}
          disabled={currentState === "excited" || targetState !== null}
        >
          Show Responses
        </button>
        <button
          className={`px-3 py-1 text-xs rounded-full transition-colors ${
            currentState === "decaying" || targetState !== null
              ? "bg-gray-300 text-gray-600 cursor-not-allowed"
              : "bg-gray-600 text-white hover:bg-gray-700"
          }`}
          onClick={() => onStateChange("decay")}
          disabled={currentState === "decaying" || targetState !== null}
        >
          Show Complexes
        </button>
        <button
          className={`px-3 py-1 text-xs rounded-full transition-colors ${
            currentState === "stable" || targetState !== null
              ? "bg-gray-300 text-gray-600 cursor-not-allowed"
              : "bg-gray-800 text-white hover:bg-black"
          }`}
          onClick={() => onStateChange("stabilize")}
          disabled={currentState === "stable" || targetState !== null}
        >
          Show Stimuli
        </button>
      </div>
    </div>
  )
}

export default PhysicsStateMachine

