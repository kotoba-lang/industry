// Setup necessary test environment configurations
import '@testing-library/jest-dom';

// Mock Next.js router
jest.mock('next/router', () => ({
  useRouter: () => ({
    route: '/',
    pathname: '',
    query: {},
    asPath: '',
    push: jest.fn(),
    replace: jest.fn(),
    reload: jest.fn(),
    back: jest.fn(),
    prefetch: jest.fn(),
    beforePopState: jest.fn(),
    events: {
      on: jest.fn(),
      off: jest.fn(),
      emit: jest.fn(),
    },
    isFallback: false,
  }),
}));

// Mock window.matchMedia
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: jest.fn().mockImplementation(query => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(), // Deprecated
    removeListener: jest.fn(), // Deprecated
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

// Mock IntersectionObserver
global.IntersectionObserver = class IntersectionObserver {
  constructor() {
    this.root = null;
    this.rootMargin = '';
    this.thresholds = [];
    this.callback = () => {};
  }

  observe() {
    // Implementation not needed for this mock
    return null;
  }

  unobserve() {
    // Implementation not needed for this mock
    return null;
  }

  disconnect() {
    // Implementation not needed for this mock
    return null;
  }
};

// Mock SpeechRecognition
class MockSpeechRecognition {
  constructor() {
    this.start = jest.fn();
    this.stop = jest.fn();
    this.abort = jest.fn();
    this.continuous = false;
    this.interimResults = true;
    this.lang = 'en-US';
    this.onstart = null;
    this.onresult = null;
    this.onerror = null;
    this.onend = null;
  }
}

global.SpeechRecognition = MockSpeechRecognition;
global.webkitSpeechRecognition = MockSpeechRecognition;

// Mock AudioContext and related APIs
class MockAudioContext {
  constructor() {
    this.createMediaStreamSource = jest.fn().mockReturnValue({
      connect: jest.fn()
    });
    this.createAnalyser = jest.fn().mockReturnValue({
      connect: jest.fn(),
      fftSize: 0,
      getByteFrequencyData: jest.fn()
    });
    this.createGain = jest.fn().mockReturnValue({
      connect: jest.fn(),
      gain: { value: 1 }
    });
    this.destination = {};
  }
}

global.AudioContext = MockAudioContext;
global.webkitAudioContext = MockAudioContext;

// Mock MediaRecorder
class MockMediaRecorder {
  constructor() {
    this.start = jest.fn();
    this.stop = jest.fn();
    this.ondataavailable = null;
    this.onstop = null;
    this.state = 'inactive';
  }
}

global.MediaRecorder = MockMediaRecorder;

// Mock getUserMedia
navigator.mediaDevices = {
  getUserMedia: jest.fn().mockResolvedValue({
    getTracks: () => [{stop: jest.fn()}]
  })
};

// Mock IndexedDB
const indexedDB = {
  open: jest.fn().mockReturnValue({
    onupgradeneeded: null,
    onsuccess: null,
    onerror: null,
    result: {
      transaction: jest.fn().mockReturnValue({
        objectStore: jest.fn().mockReturnValue({
          put: jest.fn(),
          get: jest.fn(),
          getAll: jest.fn(),
          delete: jest.fn()
        })
      })
    }
  })
};

global.indexedDB = indexedDB;

// Mock URL methods
global.URL.createObjectURL = jest.fn().mockReturnValue('blob:test');
global.URL.revokeObjectURL = jest.fn();

// Mock HTMLMediaElement
Object.defineProperty(global.HTMLMediaElement.prototype, 'play', {
  value: jest.fn().mockResolvedValue(undefined)
});

Object.defineProperty(global.HTMLMediaElement.prototype, 'pause', {
  value: jest.fn()
});

// Mock Blob
global.Blob = class MockBlob {
  constructor(content, options) {
    this.content = content;
    this.options = options;
    this.size = 1024;
    this.type = options?.type || '';
  }
}; 