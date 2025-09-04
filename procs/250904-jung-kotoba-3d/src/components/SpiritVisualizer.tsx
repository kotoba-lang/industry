import React, { Suspense, useState, useEffect } from 'react';
import { Canvas } from '@react-three/fiber';
import { OrbitControls, Text, Html } from '@react-three/drei';
import * as THREE from 'three';
import { Vector, Association } from './types';
import Points from './Points';
import Lines from './Lines';

// データ処理ロジック
const processSessionData = (sessionData: any) => {
  const events = sessionData.events;
  const wordAssociations = new Map();
  const reactionTimes = new Map();

  let currentWord: string | null = null;
  let displayTime: number | null = null;

  for (const event of events) {
    if (event.type === "word_displayed") {
      currentWord = event.payload.word;
      displayTime = event.timestamp;
    } else if (event.type === "speech_detected" && currentWord && displayTime) {
      const reactionTime = event.timestamp - displayTime;
      const responseWord = event.payload.word || "unknown";

      if (!reactionTimes.has(currentWord)) {
        reactionTimes.set(currentWord, []);
      }
      reactionTimes.get(currentWord).push(reactionTime);

      const key = `${currentWord}->${responseWord}`;
      wordAssociations.set(key, (wordAssociations.get(key) || 0) + 1);

      currentWord = null;
      displayTime = null;
    }
  }

  const words = Array.from(
    new Set([
      ...Array.from(reactionTimes.keys()),
      ...Array.from(wordAssociations.keys()).flatMap((k: string) => k.split("->")),
    ])
  );

  const vectors: Vector[] = words.map((word: any) => {
    const times = reactionTimes.get(word) || [];
    const avgReactionTime =
      times.length > 0
        ? times.reduce((a: number, b: number) => a + b, 0) / times.length
        : 1000;

    const energy = -Math.log(avgReactionTime / 1000 + 0.001);
    const associationCount = Array.from(wordAssociations.keys()).filter((k: string) =>
      k.startsWith(`${word}->`)
    ).length;

    return {
      word,
      vector: [
        (Math.random() - 0.5) * 10,
        energy,
        (Math.random() - 0.5) * 10,
      ],
      reactionTime: avgReactionTime,
      associationCount,
    };
  });

  const associations: Association[] = Array.from(wordAssociations.entries()).map(([key, strength]) => {
    const [source, target] = (key as string).split('->');
    return { source, target, strength };
  });

  return { vectors, associations };
};


const SpiritVisualizer: React.FC = () => {
  const [vectors, setVectors] = useState<Vector[]>([]);
  const [associations, setAssociations] = useState<Association[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    fetch('/session_data.json')
      .then((res) => res.json())
      .then((sessionData) => {
        const { vectors, associations } = processSessionData(sessionData);
        setVectors(vectors);
        setAssociations(associations);
        setIsLoading(false);
      });
  }, []);

  if (isLoading) {
    return (
      <Html center>
        <div style={{ color: 'white', fontSize: '1.5rem' }}>データを読み込み中...</div>
      </Html>
    );
  }

  return (
    <Canvas camera={{ position: [0, 0, 15], fov: 75 }}>
      <color attach="background" args={['#101010']} />
      <ambientLight intensity={0.5} />
      <pointLight position={[10, 10, 10]} />
      <Suspense fallback={
        <Html center>
          <div style={{ color: 'white' }}>Loading 3D Objects...</div>
        </Html>
      }>
        <Points vectors={vectors} />
        <Lines associations={associations} vectors={vectors} />
      </Suspense>
      <OrbitControls />
    </Canvas>
  );
};

export default SpiritVisualizer;
