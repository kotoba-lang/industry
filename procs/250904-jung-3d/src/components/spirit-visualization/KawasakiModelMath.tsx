"use client";

import React from "react";

export default function KawasakiModelMath() {
  return (
    <div className="bg-gradient-to-r from-indigo-50 to-purple-50 p-8 rounded-lg border border-indigo-200">
      <h3 className="text-2xl font-bold text-indigo-900 mb-6 text-center">
        Kawasaki Model - Mathematical Foundation
      </h3>

      <div className="space-y-8">
        {/* Core Model Definition */}
        <div className="bg-white p-6 rounded-lg shadow-sm">
          <h4 className="text-lg font-semibold text-indigo-800 mb-3">
            Core Spirit Physical Space Model
          </h4>
          <div className="bg-gray-50 p-4 rounded font-mono text-sm overflow-x-auto">
            <div className="text-center text-lg mb-2">
              S = V, E, T
            </div>
            <div className="text-center text-base">
              E = -ln P(wₒ | wᵢ), V = &#123;&#7733;wᵢ, &#7733;wₒ, ...&#125;, T = time axis
            </div>
          </div>
          <p className="text-indigo-700 text-sm mt-3">
            Where S represents the spirit state, V is the vector space of word associations,
            E is the energy function based on conditional probability, and T is the temporal dimension.
          </p>
        </div>

        {/* Physical Definition */}
        <div className="bg-white p-6 rounded-lg shadow-sm">
          <h4 className="text-lg font-semibold text-indigo-800 mb-3">
            Physical Definition of Spirit
          </h4>
          <div className="bg-gray-50 p-4 rounded font-mono text-sm text-center">
            <div className="text-lg">
              ψ(S) = δE(S)/δS
            </div>
          </div>
          <p className="text-indigo-700 text-sm mt-3">
            The spirit function ψ(S) is defined as the derivative of energy with respect to
            the spirit state, providing a measurable quantity for spiritual phenomena.
          </p>
        </div>

        {/* Word Association Model */}
        <div className="bg-white p-6 rounded-lg shadow-sm">
          <h4 className="text-lg font-semibold text-indigo-800 mb-3">
            Word Association Probability Model
          </h4>
          <div className="bg-gray-50 p-4 rounded font-mono text-xs overflow-x-auto">
            <div className="text-center mb-3">
              P(wₒ | wᵢ) = [numerator]/[denominator]
            </div>
            <div className="text-center">
              exp(&#7733;wᵢ · &#7733;wₒ) · [r(wᵢ, wₒ)]^α · exp(γ ΔSP(wᵢ,wₒ)/λ) · exp(η F(wᵢ, wₒ))
            </div>
            <div className="text-center mt-2">
              Σ exp(&#7733;wᵢ · &#7733;wⱼ) · [r(wᵢ, wⱼ)]^α · exp(γ ΔSP(wᵢ,wⱼ)/λ) · exp(η F(wᵢ, wⱼ))
            </div>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mt-4 text-sm">
            <div className="bg-blue-50 p-3 rounded">
              <strong className="text-blue-800">Word2Vec:</strong>
              <p className="text-blue-700">Semantic similarity through vector dot product</p>
            </div>
            <div className="bg-green-50 p-3 rounded">
              <strong className="text-green-800">Reaction Time:</strong>
              <p className="text-green-700">Jung's association factor r(wᵢ, wₒ) = 1/T</p>
            </div>
            <div className="bg-purple-50 p-3 rounded">
              <strong className="text-purple-800">Physiology:</strong>
              <p className="text-purple-700">Skin potential response ΔSP integration</p>
            </div>
          </div>
        </div>

        {/* Key Principles */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="bg-white p-6 rounded-lg shadow-sm">
            <h4 className="text-lg font-semibold text-emerald-800 mb-3">
              🧠 Hypothesis 1: Information is Physics
            </h4>
            <p className="text-emerald-700 text-sm">
              Information is inherently physical—it obeys the laws of thermodynamics and
              directly influences energy exchange. Experimental validations of Landauer's
              principle confirm that computation and energy are fundamentally intertwined.
            </p>
          </div>

          <div className="bg-white p-6 rounded-lg shadow-sm">
            <h4 className="text-lg font-semibold text-rose-800 mb-3">
              🌌 Hypothesis 2: Self-expansiveness
            </h4>
            <p className="text-rose-700 text-sm">
              Based on the rubber hand illusion, self-boundaries are not fixed but can
              extend to incorporate external objects, providing a measurable basis for
              transforming physical self-perception into expansive spiritual states.
            </p>
          </div>
        </div>

        {/* Research Context */}
        <div className="bg-gradient-to-r from-gray-50 to-slate-50 p-6 rounded-lg">
          <h4 className="text-lg font-semibold text-gray-800 mb-3">
            Research Foundation
          </h4>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-sm">
            <div className="text-center">
              <div className="font-semibold text-gray-700">Carl Jung (1910)</div>
              <p className="text-gray-600">Word Association Test methodology</p>
            </div>
            <div className="text-center">
              <div className="font-semibold text-gray-700">Landauer (1991)</div>
              <p className="text-gray-600">Information-thermodynamics principle</p>
            </div>
            <div className="text-center">
              <div className="font-semibold text-gray-700">Botvinick & Cohen (1998)</div>
              <p className="text-gray-600">Rubber hand illusion research</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
