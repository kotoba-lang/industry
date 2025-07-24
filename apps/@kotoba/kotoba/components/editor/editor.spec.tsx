import React from 'react';
import { render } from '@testing-library/react';
import { BasicEditor } from './editor.composition.js';

it('should render the correct text', () => {
  const { getByText } = render(<BasicEditor />);
  const rendered = getByText('hello world!');
  expect(rendered).toBeTruthy();
});
