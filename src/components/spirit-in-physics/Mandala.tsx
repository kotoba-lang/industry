"use client";

import React, { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";

const Mandala: React.FC<{ handleMandalaData: (data: any) => void }> = ({
  handleMandalaData,
}) => {
  const [name, setName] = useState("");
  const [dimensions, setDimensions] = useState(Array(8).fill(""));

  const handleNameChange = (value: string) => {
    setName(value);
    handleMandalaData(value);
  };

  const handleDimensionChange = (index: number, value: string) => {
    const newDimensions = [...dimensions];
    newDimensions[index] = value;
    setDimensions(newDimensions);
    handleMandalaData(newDimensions);
  };

  const dimensionVariants = {
    hidden: { opacity: 0, scale: 0.8 },
    visible: { opacity: 1, scale: 1 },
  };

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.5 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.5 }}
    >
      <div className="w-full max-w-md mx-auto p-4">
        <h1 className="text-2xl font-bold text-center mb-6 text-black">
          MANDALA
        </h1>
        <p className="text-center mb-6 text-black">
          Place your name in the center. Then, write down the concepts that have
          the most influence on your current life around you.
        </p>
        <div className="grid grid-cols-3 gap-4 text-black">
          {[0, 1, 2, 3, null, 4, 5, 6, 7].map((index, position) => (
            <motion.div
              key={position}
              whileHover={{ scale: 1.05 }}
              whileTap={{ scale: 0.95 }}
            >
              <div
                className={`bg-white rounded-lg shadow-md overflow-hidden ${
                  index === null ? "col-start-2" : ""
                }`}
              >
                {index === null ? (
                  <input
                    type="text"
                    value={name}
                    onChange={(e) => handleNameChange(e.target.value)}
                    className="w-full h-full p-4 text-center focus:outline-none focus:ring-2 focus:ring-blue-500"
                    placeholder="Your Name"
                  />
                ) : (
                  <AnimatePresence>
                    {name && (
                      <motion.div
                        variants={dimensionVariants}
                        initial="hidden"
                        animate="visible"
                        exit="hidden"
                        transition={{ duration: 0.3 }}
                      >
                        <input
                          type="text"
                          value={dimensions[index]}
                          onChange={(e) =>
                            handleDimensionChange(index, e.target.value)
                          }
                          className="w-full h-full p-4 text-center focus:outline-none focus:ring-2 focus:ring-blue-500"
                          placeholder={`dimV ${index + 1}`}
                        />
                      </motion.div>
                    )}
                  </AnimatePresence>
                )}
              </div>
            </motion.div>
          ))}
        </div>
      </div>
    </motion.div>
  );
};

export default Mandala;
