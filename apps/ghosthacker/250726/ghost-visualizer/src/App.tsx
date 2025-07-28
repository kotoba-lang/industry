import CytoscapeComponent from 'react-cytoscapejs';
import { graphData } from './graphData';
import './App.css';

function App() {
  const layout = { name: 'cose', animate: true, fit: true, padding: 50 };

  const stylesheet = [
    {
      selector: 'node',
      style: {
        'background-color': '#666',
        'label': 'data(label)',
        'width': 'label',
        'height': 'label',
        'padding': '10px',
        'shape': 'round-rectangle',
        'text-valign': 'center',
        'text-halign': 'center',
        'color': 'white',
        'text-wrap': 'wrap',
        'text-max-width': '100px',
      },
    },
    {
      selector: 'edge',
      style: {
        'width': 2,
        'label': 'data(label)',
        'line-color': '#ccc',
        'target-arrow-color': '#ccc',
        'target-arrow-shape': 'triangle',
        'curve-style': 'bezier',
        'font-size': '10px',
        'color': '#777',
        'text-rotation': 'autorotate',
      },
    },
    {
      selector: 'node[type="character"]',
      style: {
        'background-color': '#ff4c52',
      },
    },
    {
      selector: 'node[type="concept"]',
      style: {
        'background-color': '#4caf50',
      },
    },
    {
      selector: 'node[type="event"]',
      style: {
        'background-color': '#2196f3',
        'shape': 'ellipse',
      },
    },
  ];

  return (
    <div>
      <h1>Ghost Hacker - Story Graph</h1>
      <CytoscapeComponent
        elements={CytoscapeComponent.normalizeElements(graphData)}
        style={{ width: '100vw', height: '90vh' }}
        layout={layout}
        stylesheet={stylesheet}
      />
    </div>
  );
}

export default App;
