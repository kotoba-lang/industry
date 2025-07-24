import React from 'react';
import { render } from '@testing-library/react';
import { BasicKotobaApp } from './kotoba-app.composition.js';

it('should render the correct text', () => {
  const { getByText } = render(<BasicKotobaApp />);
  const rendered = getByText('hello world!');
  expect(rendered).toBeTruthy();
});
