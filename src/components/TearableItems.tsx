"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";

const TearableItems = () => {
  const [isTorn, setIsTorn] = useState(false);
  const [isHammering, setIsHammering] = useState(false);

  const toggleTear = () => {
    setIsHammering(true);
    setTimeout(() => {
      setIsTorn((prev) => !prev);
    }, 250);
    setTimeout(() => {
      setIsHammering(false);
    }, 500);
  };

  const shakeAnimation = {
    x: [0, -5, 5, -3, 3, 0],
    y: [0, -3, 3, -2, 2, 0],
    transition: { duration: 0.3, ease: "easeInOut" },
  };

  const hammerAnimation = {
    rotate: [0, 45, 0],
    x: [0, 10, 0],
    y: [0, 10, 0],
    transition: { duration: 0.5, ease: "easeInOut" },
  };

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
      <div className="relative w-full max-w-2xl">
        <div className="flex justify-between items-center">
          <motion.div animate={isTorn ? shakeAnimation : {}}>
            <div className="bg-white p-6 rounded-lg shadow-lg w-64">
              <h2 className="text-xl font-bold mb-2">Self</h2>
            </div>
          </motion.div>
          <motion.div animate={isTorn ? shakeAnimation : {}}>
            <div className="bg-white p-6 rounded-lg shadow-lg w-64">
              <h2 className="text-xl font-bold mb-2">Partner</h2>
            </div>
          </motion.div>
        </div>
        <AnimatePresence>
          {!isTorn && (
            <motion.svg
              viewBox="0 0 100 100"
              initial={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              animate={{ opacity: 1 }}
            >
              <div className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 w-32 h-32">
                <motion.path
                  d="M0,50 C25,50 75,50 100,50"
                  stroke="#333"
                  strokeWidth="4"
                  fill="none"
                  initial={{ pathLength: 0 }}
                  animate={{ pathLength: 1 }}
                  transition={{ duration: 0.5, ease: "easeInOut" }}
                />
                <motion.path
                  d="M0,50 C25,50 75,50 100,50"
                  stroke="#fff"
                  strokeWidth="2"
                  fill="none"
                  initial={{ pathLength: 0 }}
                  animate={{ pathLength: 1 }}
                  transition={{ duration: 0.5, ease: "easeInOut", delay: 0.1 }}
                />
              </div>
            </motion.svg>
          )}
        </AnimatePresence>
        <AnimatePresence>
          {isTorn && (
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
            >
              <div className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 w-32 h-32">
                {[...Array(10)].map((_, i) => (
                  <motion.div
                    key={i}
                    initial={{
                      x: 50,
                      y: 50,
                      scale: 1,
                    }}
                    animate={{
                      x: 50 + Math.random() * 60 - 30,
                      y: 50 + Math.random() * 60 - 30,
                      scale: 0,
                      opacity: 0,
                    }}
                    transition={{
                      duration: 0.5 + Math.random() * 0.5,
                      ease: "easeOut",
                    }}
                  >
                    <div className="w-1 h-1 bg-gray-400"></div>
                  </motion.div>
                ))}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
        <motion.div
          animate={isHammering ? hammerAnimation : {}}
          style={{ originX: 0.8, originY: 0.9 }}
        >
          <svg
            className="absolute top-0 right-0 transform translate-x-1/2 -translate-y-1/2 w-32 h-32"
            viewBox="0 0 100 100"
          >
            {/* Hammer head */}
            <g>
              {/* Main head */}
              <path
                d="M60,15 L80,15 L80,25 L60,25 Z"
                fill="#B8B8B8"
                stroke="#666"
                strokeWidth="1"
              />
              {/* Claw */}
              <path
                d="M80,15 Q85,15 85,20 L85,25 Q85,30 80,25 Z"
                fill="#B8B8B8"
                stroke="#666"
                strokeWidth="1"
              />
              {/* Face */}
              <path
                d="M55,15 L60,15 L60,25 L55,25 Q50,20 55,15 Z"
                fill="#B8B8B8"
                stroke="#666"
                strokeWidth="1"
              />
              {/* Metallic effect */}
              <path
                d="M62,17 L78,17"
                stroke="#999"
                strokeWidth="0.5"
                opacity="0.5"
              />
              <path
                d="M62,20 L78,20"
                stroke="#999"
                strokeWidth="0.5"
                opacity="0.5"
              />
              <path
                d="M62,23 L78,23"
                stroke="#999"
                strokeWidth="0.5"
                opacity="0.5"
              />
            </g>
            {/* Handle */}
            <g>
              {/* Main handle */}
              <path d="M67.5,25 L72.5,25 L75,90 L65,90 Z" fill="#333" />
              {/* Grip texture */}
              {[...Array(8)].map((_, i) => (
                <path
                  key={i}
                  d="M65,90 L75,90"
                  stroke="#222"
                  strokeWidth="0.5"
                  opacity="0.3"
                  transform={`translate(0, ${-i * 8})`}
                />
              ))}
            </g>
          </svg>
        </motion.div>
      </div>
      <button
        className={`mt-8 px-4 py-2 text-white rounded transition-colors ${
          isTorn
            ? "bg-blue-500 hover:bg-blue-600"
            : "bg-red-500 hover:bg-red-600"
        }`}
        onClick={toggleTear}
        disabled={isHammering}
      >
        {isTorn ? "繋げなおす" : "ハンマーで叩く"}
      </button>
    </div>
  );
};

export default TearableItems;
