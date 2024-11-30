"use client";

import { motion } from 'framer-motion';

const careerData = [
  {
    period: '2014 - Present',
    company: 'Gftd Japan株式会社',
    description: 'CEO, Cyber Security Engineer'
  },
  {
    period: "2024 - Present",
    company: "Graduate School of Medical and Dental Sciences, Niigata University / 新潟大学大学院　医歯学総合研究科　生体機能調節医学専攻　システム脳病態学",
    description: "Medical Doctoral Course / 博士課程",
  },
  {
    period: "2017 - 2022",
    company: "Kyoto - Enryaku-ji / 天台宗 比叡山延暦寺　眞那宗　大宗寺",
    description: "Monk / 僧侶",
  },
  {
    period: "2012 - 2014",
    company: "Keio University / 慶應義塾大学",
    description: "Philosophy / 文学部哲学科 科学哲学専攻",
  },
  {
    period: "2012 - 2014",
    company: "Tokyo Otaku Mode Inc.",
    description: "Software Engineer",
  },
  // 実際の経歴データに置き換えてください
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