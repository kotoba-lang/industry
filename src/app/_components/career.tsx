"use client";

import { motion } from 'framer-motion';

const careerData = [
  {
    period: "2024 - Present",
    company: "Niigata University Graduate School of Medical and Dental Sciences Department of Biofunction Regulation and Systems Brain Pathology",
    description: "Medical Doctoral Course",
  },
  {
    period: "2017 - 2022",
    company: "Kyoto - Tendai, Enryakuji Temple on Mount Hiei, Mana, Daishuji Temple",
    description: "Buddhist Scholar",
  },
  {
    period: "2012 - 2014",
    company: "Keio University",
    description: "Faculty of Letters, Department of Philosophy, Philosophy of Science",
  }
];

export function Career() {
  return (
    <section className="mb-16 md:mb-24">
      <motion.h2 
        className="mb-8 text-3xl md:text-4xl font-bold tracking-tighter leading-tight"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        Career
      </motion.h2>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {careerData.map((item, index) => (
          <motion.div
            key={index}
            className="border p-6 rounded-lg shadow-sm hover:shadow-md transition-shadow"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: index * 0.1 }}
          >
            <h3 className="text-xl font-semibold mb-2">{item.company}</h3>
            <p className="text-gray-600 mb-2">{item.period}</p>
            <p className="text-gray-700">{item.description}</p>
          </motion.div>
        ))}
      </div>
    </section>
  );
}