import type { ElementsDefinition } from 'cytoscape';

export const graphData: ElementsDefinition = {
  nodes: [
    // Characters
    { data: { id: 'akito', label: 'Akito (blessroot)', type: 'character' }, group: 'nodes' },
    { data: { id: 'ren', label: 'Ren (n0is3gate)', type: 'character' }, group: 'nodes' },
    { data: { id: 'mother', label: 'Mother', type: 'character' }, group: 'nodes' },
    { data: { id: 'daughter', label: 'Daughter', type: 'character' }, group: 'nodes' },

    // Concepts
    { data: { id: 'god', label: 'God (YHWH)', type: 'concept' }, group: 'nodes' },
    { data: { id: 'soul', label: 'Soul', type: 'concept' }, group: 'nodes' },
    { data: { id: 'tree_of_life', label: 'Tree of Life', type: 'concept' }, group: 'nodes' },
    { data: { id: 'good_vibes', label: '“いい感じ” (Good Vibes)', type: 'concept' }, group: 'nodes' },
    { data: { id: 'is_system', label: 'IS (Information System)', type: 'concept' }, group: 'nodes' },
    { data: { id: 'yaoyorozu', label: 'Yaoyorozu System', type: 'concept' }, group: 'nodes' },

    // Events
    { data: { id: 'bbs_thread', label: '18-Year BBS Thread', type: 'event' }, group: 'nodes' },
    { data: { id: 'ayahuasca', label: 'Ayahuasca Sessions', type: 'event' }, group: 'nodes' },
    { data: { id: 'mothers_death', label: 'Mother’s Death', type: 'event' }, group: 'nodes' },
  ],
  edges: [
    // Relationships between characters
    { data: { source: 'mother', target: 'akito', label: 'Installs Ren\'s soul into Akito' }, group: 'edges' },
    { data: { source: 'mother', target: 'ren', label: 'Gives birth to / loses Ren' }, group: 'edges' },
    { data: { source: 'akito', target: 'ren', label: 'Integrates with Ren' }, group: 'edges' },
    { data: { source: 'akito', target: 'daughter', label: 'Becomes a father' }, group: 'edges' },

    // Character-Concept relationships
    { data: { source: 'akito', target: 'god', label: 'Fears, then redefines God' }, group: 'edges' },
    { data: { source: 'ren', target: 'god', label: 'Denies/Hacks God' }, group: 'edges' },
    { data: { source: 'akito', target: 'is_system', label: 'Creates IS' }, group: 'edges' },
    { data: { source: 'ren', target: 'yaoyorozu', label: 'Creates Yaoyorozu' }, group: 'edges' },
    { data: { source: 'akito', target: 'soul', label: 'Sees soul as a structure' }, group: 'edges' },
    { data: { source: 'ren', target: 'soul', label: 'Sees soul as a protocol' }, group: 'edges' },
    { data: { source: 'akito', target: 'tree_of_life', label: 'Connects to Tree of Life' }, group: 'edges' },
    { data: { source: 'akito', target: 'good_vibes', label: 'Achieves "Good Vibes" decision making' }, group: 'edges' },

    // Event relationships
    { data: { source: 'akito', target: 'bbs_thread', label: 'Initiates dialogue' }, group: 'edges' },
    { data: { source: 'ren', target: 'bbs_thread', label: 'Responds and debates' }, group: 'edges' },
    { data: { source: 'akito', target: 'ayahuasca', label: 'Uses to debug his soul' }, group: 'edges' },
    { data: { source: 'mother', target: 'ayahuasca', label: 'Participates in final session' }, group: 'edges' },
    { data: { source: 'ayahuasca', target: 'ren', label: 'Reveals Ren\'s true nature' }, group: 'edges' },
    { data: { source: 'mothers_death', target: 'akito', label: 'Understands death as a state change' }, group: 'edges' },
  ],
}; 