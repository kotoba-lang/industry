"use client";

import { useState } from "react";
import { motion } from "framer-motion";
import {
  CorrelationLevel,
  ElementPair,
  CorrelationInput,
} from "@/types/correlation";

const elements = ["私", "父", "母", "兄弟", "姉妹", "友人", "同僚", "祖父祖母", "子供"];

const correlationLevels: CorrelationLevel[] = [
  "とても近い",
  "近い",
  "どちらでもない",
  "遠い",
  "とても遠い",
];

type CorrelationFormProps = {
  onSubmit: (correlations: CorrelationInput[]) => void;
};

export default function CorrelationForm({ onSubmit }: CorrelationFormProps) {
  const [correlations, setCorrelations] = useState<CorrelationInput[]>([]);

  const handleCorrelationChange = (
    pair: ElementPair,
    level: CorrelationLevel
  ) => {
    setCorrelations((prev) => {
      const index = prev.findIndex(
        (c) =>
          c.pair.element1 === pair.element1 && c.pair.element2 === pair.element2
      );
      if (index !== -1) {
        return [
          ...prev.slice(0, index),
          { pair, level },
          ...prev.slice(index + 1),
        ];
      }
      return [...prev, { pair, level }];
    });
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit(correlations);
  };

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.5 }}
    >
      <form
        onSubmit={handleSubmit}
        className="space-y-6 p-6 bg-white rounded-lg shadow-md"
      >
        <h2 className="text-2xl font-bold mb-4">
          要素間の相関性を選択してください
        </h2>
        {elements.flatMap((element1, index) =>
          elements.slice(index + 1).map((element2) => (
            <div key={`${element1}-${element2}`} className="space-y-2">
              <p className="font-medium">
                {element1} と {element2} の相関性:
              </p>
              <div className="flex space-x-2">
                {correlationLevels.map((level) => (
                  <motion.div
                    key={level}
                    whileHover={{ scale: 1.05 }}
                    whileTap={{ scale: 0.95 }}
                  >
                    <button
                      onClick={() =>
                        handleCorrelationChange({ element1, element2 }, level)
                      }
                      className={`px-3 py-1 rounded ${
                        correlations.find(
                          (c) =>
                            c.pair.element1 === element1 &&
                            c.pair.element2 === element2
                        )?.level === level
                          ? "bg-blue-500 text-white"
                          : "bg-gray-200"
                      }`}
                      type="button"
                    >
                      {level}
                    </button>
                  </motion.div>
                ))}
              </div>
            </div>
          ))
        )}
        <motion.div whileHover={{ scale: 1.05 }} whileTap={{ scale: 0.95 }}>
          <button
            type="submit"
            className="w-full py-2 px-4 bg-blue-500 text-white rounded hover:bg-blue-600 transition-colors"
          >
            ベクトル化
          </button>
        </motion.div>
      </form>
    </motion.div>
  );
}
