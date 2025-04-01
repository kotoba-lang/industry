"use client"

import type React from "react"
import { useRef, useEffect, useState } from "react"
import ForceGraph3D from "react-force-graph-3d"
import type { GraphData } from "../utils/generateGraphData"
import * as THREE from "three"

interface PhysicsGraphProps {
  data: GraphData
  frameRate?: number
  time: number
  isPlaying: boolean
  speed: number
  selectedElement: string | null
  setSelectedElement: (name: string | null) => void
}

const PhysicsGraph: React.FC<PhysicsGraphProps> = ({
  data,
  frameRate = 30,
  time,
  isPlaying,
  speed,
  selectedElement,
  setSelectedElement,
}) => {
  const fgRef = useRef<any>()
  const lastRenderTime = useRef<number>(0)
  const [showAllLinks, setShowAllLinks] = useState(false)

  // Create a scene background with subtle Zen-inspired gradient
  useEffect(() => {
    const fg = fgRef.current
    if (fg && fg.scene()) {
      // Create a subtle gradient background texture
      const canvas = document.createElement("canvas")
      canvas.width = 1024
      canvas.height = 1024
      const context = canvas.getContext("2d")

      if (context) {
        // Create a subtle gradient from top to bottom
        const gradient = context.createLinearGradient(0, 0, 0, canvas.height)
        gradient.addColorStop(0, "#f5f5f5") // Very light gray at top
        gradient.addColorStop(1, "#e8e8e8") // Slightly darker at bottom

        context.fillStyle = gradient
        context.fillRect(0, 0, canvas.width, canvas.height)

        // Add subtle texture like washi paper
        context.globalAlpha = 0.03
        for (let i = 0; i < 20000; i++) {
          const x = Math.random() * canvas.width
          const y = Math.random() * canvas.height
          const size = Math.random() * 2
          context.fillStyle = Math.random() > 0.5 ? "#000" : "#555"
          context.fillRect(x, y, size, size)
        }
      }

      const texture = new THREE.CanvasTexture(canvas)
      fg.scene().background = texture

      // Add subtle ambient light for Zen atmosphere
      const ambientLight = new THREE.AmbientLight(0xf0f0f0, 0.8)
      fg.scene().add(ambientLight)

      // Add directional light for subtle shadows
      const directionalLight = new THREE.DirectionalLight(0xffffff, 0.5)
      directionalLight.position.set(1, 1, 1)
      fg.scene().add(directionalLight)
    }
  }, [])

  // Initialize and update graph
  useEffect(() => {
    const fg = fgRef.current
    if (fg) {
      // Adjust force simulation parameters for more balanced, harmonious layout
      fg.d3Force("charge").strength(-40) // Gentler repulsion
      fg.d3Force("link")
        .distance((link: any) => {
          // Longer connections for field, shorter for particles
          if (link.source === "field" || link.target === "field") {
            return 180
          }
          // Adjust distance based on strength - more natural spacing
          return 60 + (1 - Math.min(link.strength, 1)) * 120
        })
        .strength((link: any) => {
          // Stronger connections for field
          if (link.source === "field" || link.target === "field") {
            return 0.15
          }
          // Weaker connections between particles - allows more natural arrangement
          return Math.min(link.strength * 0.04, 0.08)
        })

      // Set camera position for better viewing angle
      fg.cameraPosition({ x: 0, y: 0, z: 350 })
    }
  }, [data])

  // Frame rate control
  useEffect(() => {
    const fg = fgRef.current
    if (!fg) return

    // Save original animation loop
    const originalAnimationLoop = fg._animationCycle

    // Frame rate control variables
    const targetFrameInterval = 1000 / frameRate
    let lastFrameTime = 0

    // Override animation loop
    fg._animationCycle = (timestamp: number) => {
      // Frame rate control
      const elapsed = timestamp - lastFrameTime

      // Render when target frame interval is reached
      if (elapsed >= targetFrameInterval) {
        // Call the original animation cycle in the context of fg
        if (typeof originalAnimationLoop === 'function') {
          originalAnimationLoop.call(fg, timestamp)
        } else {
          // Fallback rendering - use the ForceGraph's render method
          fg.frameId = requestAnimationFrame(fg._animationCycle);
          fg.renderer().render(fg.scene(), fg.camera());
        }
        lastFrameTime = timestamp
      }

      // Request next frame
      fg._animationFrameId = requestAnimationFrame(fg._animationCycle)
    }

    // Start animation
    fg._animationFrameId = requestAnimationFrame(fg._animationCycle)

    // Cleanup
    return () => {
      if (fg._animationFrameId) {
        cancelAnimationFrame(fg._animationFrameId)
      }
    }
  }, [frameRate])

  // Zen-inspired link colors - subtle, ink-like gradations
  const getLinkColor = (strength: number) => {
    if (strength <= 0.5) {
      // Very faint, like diluted ink
      return `rgba(45, 45, 45, ${0.05 + strength * 0.05})`
    } else if (strength <= 1) {
      // Medium tone, like light sumi-e brush stroke
      return `rgba(40, 40, 40, ${0.1 + strength * 0.1})`
    } else if (strength <= 2) {
      // Darker tone, more defined brush stroke
      return `rgba(35, 35, 35, ${0.15 + strength * 0.15})`
    } else {
      // Darkest, like concentrated ink
      return `rgba(30, 30, 30, ${0.2 + strength * 0.2})`
    }
  }

  // Create text sprite function with Zen-inspired aesthetics
  const createTextSprite = (text: string, size = 1, isSelected = false) => {
    const canvas = document.createElement("canvas")
    const context = canvas.getContext("2d")
    if (!context) return null

    // Set canvas size
    canvas.width = 256
    canvas.height = 128

    // Draw background - transparent for Zen simplicity
    context.clearRect(0, 0, canvas.width, canvas.height)

    // Draw text with Zen-inspired styling
    context.font = `${isSelected ? "bold " : ""}24px 'Helvetica', sans-serif`
    context.fillStyle = isSelected ? "#000000" : "#333333"
    context.textAlign = "center"
    context.textBaseline = "middle"

    // Add subtle shadow for depth
    if (isSelected) {
      context.shadowColor = "rgba(0, 0, 0, 0.2)"
      context.shadowBlur = 4
      context.shadowOffsetX = 2
      context.shadowOffsetY = 2
    }

    context.fillText(text, canvas.width / 2, canvas.height / 2)

    // Create texture
    const texture = new THREE.Texture(canvas)
    texture.needsUpdate = true

    // Create sprite material
    const spriteMaterial = new THREE.SpriteMaterial({
      map: texture,
      transparent: true,
    })

    // Create sprite
    const sprite = new THREE.Sprite(spriteMaterial)
    sprite.scale.set(size * 20, size * 10, 1)

    return sprite
  }

  // Create node object function with Zen-inspired aesthetics
  const createNodeObject = (node: any) => {
    const group = new THREE.Group()
    const isField = node.id === "field"
    const isSelected = node.name === selectedElement

    // Node size - field is larger, selected nodes are slightly larger
    const size = isField ? 12 : isSelected ? 5 : 3.5

    // Create geometry based on node type for visual variety
    let geometry
    let material

    if (isField) {
      // Field node as a simple sphere - represents emptiness/wholeness
      geometry = new THREE.SphereGeometry(size, 32, 32)
      material = new THREE.MeshPhongMaterial({
        color: 0x2c2c2c,
        transparent: true,
        opacity: 0.9,
        shininess: 30,
      })
    } else if (node.group === 3) {
      // Special nodes as octahedrons - represents crystalline structure
      geometry = new THREE.OctahedronGeometry(size, 0)
      material = new THREE.MeshPhongMaterial({
        color: isSelected ? 0x8c4a3a : 0x8c6a5a,
        transparent: true,
        opacity: 0.85,
        shininess: 50,
      })
    } else {
      // Regular nodes as dodecahedrons - represents natural forms
      geometry = new THREE.DodecahedronGeometry(size, 0)
      material = new THREE.MeshPhongMaterial({
        color: isSelected ? 0x3a4a5a : 0x5a6a7a,
        transparent: true,
        opacity: 0.8,
        shininess: 40,
      })
    }

    const mesh = new THREE.Mesh(geometry, material)

    // Add subtle animation for selected nodes
    if (isSelected) {
      const pulseAnimation = () => {
        const scale = 1 + Math.sin(Date.now() * 0.003) * 0.1
        mesh.scale.set(scale, scale, scale)
        requestAnimationFrame(pulseAnimation)
      }
      pulseAnimation()
    }

    group.add(mesh)

    // Create text sprite
    const textSprite = createTextSprite(node.name, isField ? 1.8 : 1.2, isSelected)
    if (textSprite) {
      textSprite.position.set(0, size + 8, 0)
      group.add(textSprite)
    }

    return group
  }

  // Toggle links visibility
  const toggleLinks = () => {
    setShowAllLinks(!showAllLinks)
  }

  // Control link visibility
  const getLinkVisibility = (link: any) => {
    // Show all links mode
    if (showAllLinks) return true

    // Always show connections to field
    if (link.source === "field" || link.target === "field") return true

    // Show links to selected element
    if (
      selectedElement &&
      ((typeof link.source === "object" && link.source.name === selectedElement) ||
        (typeof link.target === "object" && link.target.name === selectedElement))
    ) {
      return true
    }

    // Only show links with strength above threshold
    return link.strength > 0.5
  }

  // Prepare visible data
  const visibleData = {
    nodes: data.nodes,
    links: data.links.filter(getLinkVisibility),
  }

  // Extract element names from graph data (limit display)
  const elementNames = data.nodes.map((node) => node.name).slice(0, 10)
  const totalElements = data.nodes.length

  return (
    <div className="w-full h-full relative">
      <button
        className="absolute top-2 right-2 z-10 px-3 py-1 bg-gray-800 text-white rounded-full text-xs opacity-70 hover:opacity-100 transition-opacity"
        onClick={toggleLinks}
      >
        {showAllLinks ? "Hide Weak Links" : "Show All Links"}
      </button>

      {/* Element list in top-left with Zen-inspired styling */}
      <div className="absolute top-2 left-2 z-10 bg-white/60 backdrop-blur-sm p-2 rounded-md text-xs border border-gray-200 shadow-sm max-w-[200px] max-h-[200px] overflow-auto">
        <h3 className="text-xs font-bold mb-1 text-gray-800">Elements: ({totalElements})</h3>
        <div className="grid grid-cols-2 gap-1 text-xs">
          {elementNames.map((name, index) => (
            <div
              key={index}
              className={`p-1 rounded-sm cursor-pointer truncate transition-colors ${
                selectedElement === name
                  ? "bg-gray-800 text-white"
                  : "bg-white/70 hover:bg-gray-200 text-gray-800 border border-gray-300"
              }`}
              onClick={() => setSelectedElement(name === selectedElement ? null : name)}
            >
              {name}
            </div>
          ))}
          {totalElements > 10 && (
            <div className="p-1 rounded-sm bg-white/70 text-center border border-gray-300 col-span-full">
              ...and {totalElements - 10} more
            </div>
          )}
        </div>
      </div>

      <ForceGraph3D
        ref={fgRef}
        graphData={visibleData}
        nodeLabel={(node) => (node as any).name}
        linkLabel={(link) => (link as any).name}
        linkWidth={(link) => Math.min((link as any).strength, 2) / 4} // Thinner lines for Zen aesthetic
        linkColor={(link) => getLinkColor((link as any).strength)}
        linkOpacity={0.7}
        nodeRelSize={3.5}
        backgroundColor="#f5f5f5" // Light background like washi paper
        enableNodeDrag={false}
        enableNavigationControls={true}
        showNavInfo={false} // Hide navigation info for cleaner look
        nodeThreeObject={createNodeObject}
        linkCurvature={0.1} // Slight curve for more organic feel
        cooldownTime={3000}
        nodeResolution={12} // Higher resolution for smoother shapes
        warmupTicks={100}
        cooldownTicks={100}
      />
    </div>
  )
}

export default PhysicsGraph

