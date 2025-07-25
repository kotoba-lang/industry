import React from 'react';
import { KotobaApp } from './kotoba-app';

export const BasicKotobaApp = () => {
  return <KotobaApp />;
};

export const KotobaAppWithCustomTitle = () => {
  return <KotobaApp title="Custom Kotoba Platform" />;
};

export const KotobaAppWithDarkTheme = () => {
  return (
    <div className="dark">
      <KotobaApp />
    </div>
  );
}; 