import React from 'react';
import { render } from '@testing-library/react';
import { BasicHost } from './host.composition.js';

it('should render the correct text', () => {
  const { getByText } = render(<BasicHost />);
  const rendered = getByText('hello world!');
  expect(rendered).toBeTruthy();
});
