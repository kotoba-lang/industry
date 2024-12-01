'use client'

import { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'

const TearableItems = () => {
  const [isTorn, setIsTorn] = useState(false)

  const toggleTear = () => {
    setIsTorn(!isTorn)
  }

  const shakeAnimation = {
    x: [0, -5, 5, -3, 3, 0],
    y: [0, -3, 3, -2, 2, 0],
    transition: { duration: 0.3, ease: "easeInOut" }
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
      <div className="relative w-full max-w-2xl">
        <div className="flex justify-between items-center">
          <motion.div 
            className="bg-white p-6 rounded-lg shadow-lg w-64"
            animate={isTorn ? shakeAnimation : {}}
          >
            <h2 className="text-xl font-bold mb-2">河崎純真</h2>
            <p>ここに項目1の内容を記述します。</p>
          </motion.div>
          <motion.div 
            className="bg-white p-6 rounded-lg shadow-lg w-64"
            animate={isTorn ? shakeAnimation : {}}
          >
            <h2 className="text-xl font-bold mb-2">Apple Vi</h2>
            <p>ここに項目2の内容を記述します。</p>
          </motion.div>
        </div>
        <AnimatePresence>
          {!isTorn && (
            <motion.svg
              className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 w-32 h-32"
              viewBox="0 0 100 100"
              initial={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              onClick={toggleTear}
            >
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
            </motion.svg>
          )}
        </AnimatePresence>
        <AnimatePresence>
          {isTorn && (
            <motion.div
              className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 w-32 h-32"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
            >
              {[...Array(10)].map((_, i) => (
                <motion.div
                  key={i}
                  className="absolute w-1 h-1 bg-gray-400"
                  initial={{ 
                    x: 50, 
                    y: 50, 
                    scale: 1 
                  }}
                  animate={{ 
                    x: 50 + Math.random() * 60 - 30, 
                    y: 50 + Math.random() * 60 - 30, 
                    scale: 0,
                    opacity: 0
                  }}
                  transition={{ 
                    duration: 0.5 + Math.random() * 0.5, 
                    ease: "easeOut" 
                  }}
                />
              ))}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
      <button
        className={`mt-8 px-4 py-2 text-white rounded transition-colors ${
          isTorn 
            ? 'bg-blue-500 hover:bg-blue-600' 
            : 'bg-red-500 hover:bg-red-600'
        }`}
        onClick={toggleTear}
      >
        {isTorn ? '繋げる' : 'ビリビリ切る'}
      </button>
    </div>
  )
}

export default TearableItems

