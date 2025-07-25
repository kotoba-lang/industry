import { render, screen } from '@testing-library/react';
import { BasicGraph } from './graph.composition';

describe('Graph component', () => {
  it('should render the graph container', () => {
    render(<BasicGraph />);
    const rendered = screen.getByTestId('graph-container');
    expect(rendered).toBeInTheDocument();
  });
});
