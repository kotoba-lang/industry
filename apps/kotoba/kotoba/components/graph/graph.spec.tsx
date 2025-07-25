import React from 'react';
import { render } from '@testing-library/react';
import { BasicGraph } from './graph.composition';

it('should render the correct text', () => {
  const { getByText } = render(<BasicGraph />);
  const rendered = getByText('hello world!');
  expect(rendered).toBeTruthy();
});
