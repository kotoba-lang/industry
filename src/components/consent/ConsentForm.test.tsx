import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { vi } from 'vitest';
import ConsentForm from './ConsentForm';
import { saveConsentAndDemographicData } from '@/lib/actions/consent-actions';

// Mock the server action
vi.mock('@/lib/actions/consent-actions', () => ({
  saveConsentAndDemographicData: vi.fn().mockResolvedValue({ 
    success: true,
    userId: 'test-user-id',
    consent: {},
    demographic: {}
  }),
}));

/**
 * 重要度: 5
 * このコンポーネントは研究に関する同意と個人情報を扱うため、
 * データの正確な収集と処理は倫理的・法的に極めて重要です。
 */
describe('ConsentForm', () => {
  const mockOnConsent = vi.fn();
  
  beforeEach(() => {
    vi.clearAllMocks();
    // Mock window.navigator
    Object.defineProperty(window, 'navigator', {
      value: {
        userAgent: 'test-user-agent',
      },
      writable: true,
    });
  });
  
  it('同意フォームが正しくレンダリングされること', () => {
    render(<ConsentForm onConsent={mockOnConsent} />);
    
    // Check main elements
    expect(screen.getByText('Research Participation Consent')).toBeInTheDocument();
    expect(screen.getByText('Read Full Consent Form')).toBeInTheDocument();
    expect(screen.getByText('Demographic Information (CDISC Standards)')).toBeInTheDocument();
  });
  
  it('同意フォームを展開できること', () => {
    render(<ConsentForm onConsent={mockOnConsent} />);
    
    // Toggle to show consent form
    fireEvent.click(screen.getByText('Read Full Consent Form'));
    
    // Check if consent details are now visible
    expect(screen.getByText('Research Purpose')).toBeInTheDocument();
    expect(screen.getByText('Research Procedure')).toBeInTheDocument();
    expect(screen.getByText('Confidentiality')).toBeInTheDocument();
  });
  
  it('デモグラフィック情報を選択できること', async () => {
    render(<ConsentForm onConsent={mockOnConsent} />);
    
    // Set age group
    fireEvent.click(screen.getByRole('combobox', { name: /age group/i }));
    await waitFor(() => {
      fireEvent.click(screen.getByText('25-34'));
    });
    
    // Set gender
    fireEvent.click(screen.getByLabelText('Male'));
    
    // Set ethnicity
    fireEvent.click(screen.getByRole('combobox', { name: /race\/ethnicity/i }));
    await waitFor(() => {
      fireEvent.click(screen.getByText('Prefer not to say'));
    });
    
    // Set income
    fireEvent.click(screen.getByRole('combobox', { name: /annual income/i }));
    await waitFor(() => {
      fireEvent.click(screen.getByText('$25,000 - $50,000'));
    });
    
    // Toggle consent checkbox
    fireEvent.click(screen.getByText(/I have read and understood the above information/i)); 
    
    // Check if form can be submitted
    const submitButton = screen.getByRole('button', { name: /consent and continue/i });
    expect(submitButton).not.toBeDisabled();
  });
  
  it('フォーム送信時にデータが正しく処理されること', async () => {
    render(<ConsentForm onConsent={mockOnConsent} />);
    
    // Fill form data
    fireEvent.click(screen.getByRole('combobox', { name: /age group/i }));
    await waitFor(() => {
      fireEvent.click(screen.getByText('25-34'));
    });
    
    fireEvent.click(screen.getByLabelText('Female'));
    
    fireEvent.click(screen.getByRole('combobox', { name: /race\/ethnicity/i }));
    await waitFor(() => {
      fireEvent.click(screen.getByText('White'));
    });
    
    fireEvent.click(screen.getByRole('combobox', { name: /annual income/i }));
    await waitFor(() => {
      fireEvent.click(screen.getByText('Prefer not to say'));
    });
    
    fireEvent.click(screen.getByText(/I have read and understood the above information/i));
    
    // Submit form
    fireEvent.click(screen.getByRole('button', { name: /consent and continue/i }));
    
    // Wait for async operations to complete
    await waitFor(() => {
      // Check if saveConsentAndDemographicData was called with correct data
      expect(saveConsentAndDemographicData).toHaveBeenCalledWith(
        expect.objectContaining({
          ageGroup: '25-34',
          gender: 'female',
          ethnicity: 'white',
          income: 'prefer-not-to-say'
        }),
        expect.objectContaining({
          consentGiven: true,
          consentVersion: '1.0',
          consentText: expect.any(String)
        }),
        expect.objectContaining({
          userAgent: 'test-user-agent',
          studyId: 'SPIRIT-IN-PHYSICS-2025'
        })
      );
      
      // Check if onConsent callback was called
      expect(mockOnConsent).toHaveBeenCalled();
    });
  });
  
  it('送信中は送信ボタンが無効化されること', async () => {
    // Make saveConsentAndDemographicData slow to resolve
    vi.mocked(saveConsentAndDemographicData).mockImplementationOnce(() => 
      new Promise(resolve => {
        setTimeout(() => resolve({ 
          success: true,
          userId: 'test-user-id',
          consent: {},
          demographic: {}
        }), 100);
      })
    );
    
    render(<ConsentForm onConsent={mockOnConsent} />);
    
    // Fill required fields and submit
    fireEvent.click(screen.getByRole('combobox', { name: /age group/i }));
    await waitFor(() => {
      fireEvent.click(screen.getByText('25-34'));
    });
    
    fireEvent.click(screen.getByLabelText('Female'));
    
    fireEvent.click(screen.getByText(/I have read and understood the above information/i));
    
    const submitButton = screen.getByRole('button', { name: /consent and continue/i });
    fireEvent.click(submitButton);
    
    // Button should be disabled and show loading state
    expect(submitButton).toBeDisabled();
    
    // Wait for submission to complete
    await waitFor(() => {
      expect(mockOnConsent).toHaveBeenCalled();
    });
  });
}); 