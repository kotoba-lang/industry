import { render, screen } from '@testing-library/react';
import { BasicHost } from './host.composition';

describe('Host component', () => {
  it('should render the correct text', () => {
    render(<BasicHost />);
    const rendered = screen.getByText('Kotoba Platform');
    expect(rendered).toBeInTheDocument();
  });
});
