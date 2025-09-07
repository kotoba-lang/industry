import { render, screen } from '@testing-library/react';
import { BasicEditor } from './editor.composition';

describe('Editor component', () => {
  it('should render the correct text', () => {
    render(<BasicEditor />);
    const rendered = screen.getByText('Kotoba Editor');
    expect(rendered).toBeInTheDocument();
  });
});
