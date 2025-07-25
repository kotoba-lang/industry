import React from 'react';
import { render } from '@testing-library/react';
import { BasicHost } from './host.composition';

it('should render the correct text', () => {
  const { getByText } = render(<BasicHost />);
  const rendered = getByText('Kotoba Platform');
  expect(rendered).toBeTruthy();
});
