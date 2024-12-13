"use client";

import React, { useState, useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";

const generateColor = (index: number, total: number, hue: number) => {
  return `hsl(${hue + (index / total) * 60}, 70%, 50%)`;
};

type OrbitalType = {
  name: string;
  color: string;
  electrons: number;
  maxElectrons: number;
};

type ElectronConfig = {
  color: string;
  radius: number;
  electrons: { angle: number }[];
  rotationSpeed: number;
  strokeWidth: number;
  shellLabel: string;
  orbitalTypes: OrbitalType[];
};

const OrbitalType: React.FC<OrbitalType & { x: number; y: number }> = ({
  name,
  x,
  y,
  color,
  electrons,
}) => (
  <g>
    <rect
      x={x}
      y={y}
      width="20"
      height="20"
      fill={color}
      stroke="#61DAFB"
      strokeWidth="1"
      opacity="0.7"
    />
    <text
      x={x + 10}
      y={y + 15}
      fontSize="12"
      fontFamily="Arial, sans-serif"
      fill="#000"
      textAnchor="middle"
    >
      {electrons}
    </text>
  </g>
);

const Orbital: React.FC<ElectronConfig> = ({
  radius,
  strokeWidth,
  color,
  rotationSpeed,
  electrons,
  shellLabel,
  orbitalTypes,
}) => {
  const [rotation, setRotation] = useState(0);
  const requestRef = useRef<number | null>(null);

  const animate = () => {
    setRotation((prevRotation) => (prevRotation + rotationSpeed) % 360);
    requestRef.current = requestAnimationFrame(animate);
  };

  useEffect(() => {
    requestRef.current = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(requestRef.current!);
  }, []);

  const orbitalColors = orbitalTypes.map((_, index) =>
    generateColor(index, orbitalTypes.length, 0)
  );

  const createOrbitalPath = (
    r: number,
    type: string,
    index: number,
    orbitalColor: string
  ) => {
    switch (type) {
      case "s":
        return (
          <circle
            cx="0"
            cy="0"
            r={r}
            fill="none"
            stroke={orbitalColor}
            strokeWidth={strokeWidth}
            opacity="0.5"
          />
        );
      case "p":
        return (
          <g>
            <ellipse
              cx="0"
              cy="0"
              rx={r}
              ry={r * 0.5}
              fill="none"
              stroke={orbitalColor}
              strokeWidth={strokeWidth}
              opacity="0.5"
            />
            <ellipse
              cx="0"
              cy="0"
              rx={r}
              ry={r * 0.5}
              fill="none"
              stroke={orbitalColor}
              strokeWidth={strokeWidth}
              opacity="0.5"
              transform={`rotate(90)`}
            />
            <ellipse
              cx="0"
              cy="0"
              rx={r}
              ry={r * 0.5}
              fill="none"
              stroke={orbitalColor}
              strokeWidth={strokeWidth}
              opacity="0.5"
              transform={`rotate(90) skewX(45)`}
            />
          </g>
        );
      case "d":
        return (
          <g>
            {[0, 45, 90, 135].map((angle) => (
              <ellipse
                key={angle}
                cx="0"
                cy="0"
                rx={r}
                ry={r * 0.3}
                fill="none"
                stroke={orbitalColor}
                strokeWidth={strokeWidth}
                opacity="0.5"
                transform={`rotate(${angle}) skewX(45)`}
              />
            ))}
          </g>
        );
      case "f":
        return (
          <g>
            {[0, 30, 60, 90, 120, 150].map((angle) => (
              <ellipse
                key={angle}
                cx="0"
                cy="0"
                rx={r}
                ry={r * 0.2}
                fill="none"
                stroke={orbitalColor}
                strokeWidth={strokeWidth}
                opacity="0.5"
                transform={`rotate(${angle}) skewX(60)`}
              />
            ))}
          </g>
        );
      default:
        return null;
    }
  };

  return (
    <>
      <g transform={`rotate(${rotation})`}>
        {orbitalTypes.map((type, index) => (
          <g key={index}>
            {createOrbitalPath(
              radius + index * 30,
              type.name[1],
              index,
              orbitalColors[index]
            )}
          </g>
        ))}
        {electrons.map((electron, index) => {
          const r = radius + Math.floor(index / 2) * 30;
          const angle = (electron.angle * Math.PI) / 180;
          return (
            <circle
              key={index}
              cx={r * Math.cos(angle)}
              cy={r * Math.sin(angle)}
              r={4}
              fill="#61DAFB"
              filter="url(#glow)"
            />
          );
        })}
      </g>
      <text
        x={-radius - 50}
        y={10}
        fontSize={16}
        fontFamily="Arial, sans-serif"
        fontWeight="bold"
        fill="#61DAFB"
        textAnchor="end"
      >
        {shellLabel}
      </text>
      <g transform="translate(220, -280)">
        {orbitalTypes.map((type, index) => (
          <g key={index} transform={`translate(0, ${index * 25})`}>
            <OrbitalType
              name={type.name}
              x={0}
              y={0}
              color={type.color}
              electrons={type.electrons}
              maxElectrons={type.maxElectrons}
            />
            <text
              x={-10}
              y={15}
              fontSize={12}
              fontFamily="Arial, sans-serif"
              fill="#61DAFB"
              textAnchor="end"
            >
              {type.name}
            </text>
          </g>
        ))}
      </g>
    </>
  );
};

const Nucleus: React.FC<{ protons: number; neutrons: number }> = ({
  protons,
  neutrons,
}) => {
  const totalParticles = protons + neutrons;
  const radius = 20 + Math.sqrt(totalParticles) * 2;

  return (
    <g>
      <circle cx={0} cy={0} r={radius} fill="#2C3E50" filter="url(#glow)" />
      {Array.from({ length: totalParticles }).map((_, index) => {
        const angle = (index / totalParticles) * Math.PI * 2;
        const particleRadius = radius * 0.8;
        const x = Math.cos(angle) * particleRadius;
        const y = Math.sin(angle) * particleRadius;
        const isProton = index < protons;

        return (
          <circle
            key={index}
            cx={x}
            cy={y}
            r={3}
            fill={isProton ? "#FFA500" : "#3498DB"}
            filter="url(#glow)"
          />
        );
      })}
      <text
        x={0}
        y={5}
        fontSize={14}
        fontFamily="Arial, sans-serif"
        fontWeight="bold"
        fill="#FFFFFF"
        textAnchor="middle"
      >
        {protons}p {neutrons}n
      </text>
    </g>
  );
};

const orbitalOrder = [
  "1s",
  "2s",
  "2p",
  "3s",
  "3p",
  "4s",
  "3d",
  "4p",
  "5s",
  "4d",
  "5p",
  "6s",
  "4f",
  "5d",
  "6p",
  "7s",
  "5f",
];

const maxElectrons: { [key: string]: number } = {
  s: 2,
  p: 6,
  d: 10,
  f: 14,
};

export function SpiritModel({
  electron,
  proton,
  neutron,
  handleAtomData,
}: {
  electron: number;
  proton: number;
  neutron: number;
  handleAtomData: (atomData: any) => void;
}) {
  const [electronCount, setElectronCount] = useState(electron);
  const [protonCount, setProtonCount] = useState(proton);
  const [neutronCount, setNeutronCount] = useState(neutron);

  useEffect(() => {
    setElectronCount(electron);
    setProtonCount(proton);
    setNeutronCount(neutron);
  }, [electron, proton, neutron]);

  const getElectronConfig = (count: number): ElectronConfig[] => {
    let remaining = count;
    const config: ElectronConfig[] = [];
    let shellIndex = 0;
    const shells = ["K", "L", "M", "N"];

    for (const orbital of orbitalOrder) {
      const shell = orbital[0];
      const type = orbital[1];
      const max = maxElectrons[type];
      const electrons = Math.min(remaining, max);
      remaining -= electrons;

      if (
        config.length === 0 ||
        config[config.length - 1].shellLabel !== shells[parseInt(shell) - 1]
      ) {
        config.push({
          color: generateColor(shellIndex, shells.length, 180),
          radius: 50 + shellIndex * 50,
          electrons: [],
          rotationSpeed: 1 / (shellIndex + 1),
          strokeWidth: 2,
          shellLabel: shells[parseInt(shell) - 1],
          orbitalTypes: [],
        });
        shellIndex++;
      }

      config[config.length - 1].orbitalTypes.push({
        name: orbital,
        color: generateColor(
          config[config.length - 1].orbitalTypes.length,
          4,
          0
        ),
        electrons: electrons,
        maxElectrons: max,
      });

      for (let i = 0; i < electrons; i++) {
        config[config.length - 1].electrons.push({
          angle: (i / max) * 360,
        });
      }

      if (remaining === 0) break;
    }

    return config;
  };

  const electronConfig = getElectronConfig(electronCount);

  return (
    <div className="w-full h-screen bg-gray-900 flex flex-col items-center justify-center">
      {/* <div className="mb-4 flex space-x-4">
        <Button onClick={() => setElectronCount(Math.max(1, electronCount - 1))}>
          Remove Electron
        </Button>
        <Button onClick={() => setElectronCount(electronCount + 1)}>
          Add Electron
        </Button>
        <Button onClick={() => setProtonCount(Math.max(1, protonCount - 1))}>
          Remove Proton
        </Button>
        <Button onClick={() => setProtonCount(protonCount + 1)}>
          Add Proton
        </Button>
        <Button onClick={() => setNeutronCount(Math.max(0, neutronCount - 1))}>
          Remove Neutron
        </Button>
        <Button onClick={() => setNeutronCount(neutronCount + 1)}>
          Add Neutron
        </Button>
      </div>
      <div className="text-white mb-4">
        Electrons: {electronCount}, Protons: {protonCount}, Neutrons: {neutronCount}
      </div> */}
      <svg width="800" height="800" viewBox="-400 -400 800 800">
        <defs>
          <filter id="glow">
            <feGaussianBlur stdDeviation="3.5" result="coloredBlur" />
            <feMerge>
              <feMergeNode in="coloredBlur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
          <radialGradient
            id="bg-gradient"
            cx="50%"
            cy="50%"
            r="50%"
            fx="50%"
            fy="50%"
          >
            <stop offset="0%" stopColor="#1a202c" />
            <stop offset="100%" stopColor="#2d3748" />
          </radialGradient>
        </defs>
        <rect
          x="-400"
          y="-400"
          width="800"
          height="800"
          fill="url(#bg-gradient)"
        />
        <Nucleus protons={protonCount} neutrons={neutronCount} />
        {electronConfig.map((config, index) => (
          <Orbital
            key={index}
            radius={config.radius}
            strokeWidth={config.strokeWidth}
            color={config.color}
            rotationSpeed={config.rotationSpeed}
            electrons={config.electrons}
            shellLabel={config.shellLabel}
            orbitalTypes={config.orbitalTypes}
          />
        ))}
      </svg>
    </div>
  );
}
