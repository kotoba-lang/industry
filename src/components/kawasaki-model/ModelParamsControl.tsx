"use client"

import type React from "react"
import type { IntegratedModelParams } from "./utils/integratedModel"

interface ModelParamsControlProps {
  params: IntegratedModelParams
  onChange: (params: IntegratedModelParams) => void
}

const ModelParamsControl: React.FC<ModelParamsControlProps> = ({ params, onChange }) => {
  const handleParamChange = (param: keyof IntegratedModelParams, value: number) => {
    onChange({
      ...params,
      [param]: value,
    })
  }

  return (
    <div className="w-full bg-white/80 dark:bg-gray-800/80 backdrop-blur-sm p-3 rounded-md shadow-sm border border-gray-200 dark:border-gray-600 mb-2">
      <h3 className="text-xs font-bold mb-2 text-gray-800 dark:text-white">Integrated Model Parameters</h3>
      <div className="grid grid-cols-2 gap-3">
        <div className="flex flex-col">
          <label className="text-xs text-gray-700 dark:text-gray-300 mb-1">Reaction Speed (α): {params.alpha.toFixed(2)}</label>
          <div className="relative h-1 bg-gray-200 dark:bg-gray-600 rounded-full overflow-hidden">
            <div
              className="absolute top-0 left-0 h-full bg-gray-800 dark:bg-white rounded-full"
              style={{ width: `${(params.alpha / 3) * 100}%` }}
            ></div>
          </div>
          <input
            type="range"
            min="0"
            max="3"
            step="0.1"
            value={params.alpha}
            onChange={(e) => handleParamChange("alpha", Number.parseFloat(e.target.value))}
            className="w-full mt-1 appearance-none bg-transparent [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-gray-800"
            aria-label="Reaction Speed"
          />
        </div>

        <div className="flex flex-col">
          <label className="text-xs text-gray-700 dark:text-gray-300 mb-1">Skin Potential (γ): {params.gamma.toFixed(2)}</label>
          <div className="relative h-1 bg-gray-200 dark:bg-gray-600 rounded-full overflow-hidden">
            <div
              className="absolute top-0 left-0 h-full bg-gray-800 dark:bg-white rounded-full"
              style={{ width: `${(params.gamma / 3) * 100}%` }}
            ></div>
          </div>
          <input
            type="range"
            min="0"
            max="3"
            step="0.1"
            value={params.gamma}
            onChange={(e) => handleParamChange("gamma", Number.parseFloat(e.target.value))}
            className="w-full mt-1 appearance-none bg-transparent [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-gray-800"
            aria-label="Skin Potential"
          />
        </div>

        <div className="flex flex-col">
          <label className="text-xs text-gray-700 dark:text-gray-300 mb-1">Facial Emotion (η): {params.eta.toFixed(2)}</label>
          <div className="relative h-1 bg-gray-200 dark:bg-gray-600 rounded-full overflow-hidden">
            <div
              className="absolute top-0 left-0 h-full bg-gray-800 dark:bg-white rounded-full"
              style={{ width: `${(params.eta / 3) * 100}%` }}
            ></div>
          </div>
          <input
            type="range"
            min="0"
            max="3"
            step="0.1"
            value={params.eta}
            onChange={(e) => handleParamChange("eta", Number.parseFloat(e.target.value))}
            className="w-full mt-1 appearance-none bg-transparent [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-gray-800"
            aria-label="Facial Emotion"
          />
        </div>

        <div className="flex flex-col">
          <label className="text-xs text-gray-700 dark:text-gray-300 mb-1">Scale Factor (λ): {params.lambda.toFixed(2)}</label>
          <div className="relative h-1 bg-gray-200 dark:bg-gray-600 rounded-full overflow-hidden">
            <div
              className="absolute top-0 left-0 h-full bg-gray-800 dark:bg-white rounded-full"
              style={{ width: `${(params.lambda / 3) * 100}%` }}
            ></div>
          </div>
          <input
            type="range"
            min="0.1"
            max="3"
            step="0.1"
            value={params.lambda}
            onChange={(e) => handleParamChange("lambda", Number.parseFloat(e.target.value))}
            className="w-full mt-1 appearance-none bg-transparent [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-gray-800"
            aria-label="Scale Factor"
          />
        </div>
      </div>
    </div>
  )
}

export default ModelParamsControl

