import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { vi } from 'vitest';
import ConsentForm, { type DemographicData } from './ConsentForm';
import { saveConsentData } from '@/lib/actions/consent-actions';

// Mock the server action
vi.mock('@/lib/actions/consent-actions', () => ({
  saveConsentData: vi.fn().mockResolvedValue({ success: true }),
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
  
  it('デモグラフィック情報を選択できること', () => {
    render(<ConsentForm onConsent={mockOnConsent} />);
    
    // Set age group
    fireEvent.click(screen.getByPlaceholderText('Select age group'));
    fireEvent.click(screen.getByText('25-34'));
    
    // Set gender
    fireEvent.click(screen.getByLabelText('Male'));
    
    // Set ethnicity (assuming you're using a placeholder for the ethnicity select)
    fireEvent.click(screen.getByPlaceholderText('Select ethnicity'));
    fireEvent.click(screen.getByText('Prefer not to say')); 
    
    // Set income (assuming you're using a placeholder for the income select)
    fireEvent.click(screen.getByPlaceholderText('Select income range'));
    fireEvent.click(screen.getByText('$50,000 - $74,999')); 
    
    // Toggle consent checkbox
    fireEvent.click(screen.getByText('I agree to participate in this research')); 
    
    // Check if form can be submitted
    const submitButton = screen.getByRole('button', { name: /submit/i });
    expect(submitButton).not.toBeDisabled();
  });
  
  it('フォーム送信時にデータが正しく処理されること', async () => {
    render(<ConsentForm onConsent={mockOnConsent} />);
    
    // Fill form data
    fireEvent.click(screen.getByPlaceholderText('Select age group'));
    fireEvent.click(screen.getByText('25-34'));
    fireEvent.click(screen.getByLabelText('Female'));
    fireEvent.click(screen.getByPlaceholderText('Select ethnicity'));
    fireEvent.click(screen.getByText('White'));
    fireEvent.click(screen.getByPlaceholderText('Select income range'));
    fireEvent.click(screen.getByText('Prefer not to say'));
    fireEvent.click(screen.getByText('I agree to participate in this research'));
    
    // Submit form
    fireEvent.click(screen.getByRole('button', { name: /submit/i }));
    
    // Wait for async operations to complete
    await waitFor(() => {
      // Check if saveConsentData was called with correct data
      expect(saveConsentData).toHaveBeenCalledWith(
        {
          ageGroup: '25-34',
          gender: 'female',
          ethnicity: 'White',
          income: 'prefer-not-to-say'
        },
        {
          consentGiven: true,
          consentVersion: '1.0',
          consentText: expect.any(String)
        },
        {
          userAgent: 'test-user-agent',
          studyId: 'SPIRIT-IN-PHYSICS-2025'
        }
      );
      
      // Check if onConsent callback was called
      expect(mockOnConsent).toHaveBeenCalledWith({
        ageGroup: '25-34',
        gender: 'female',
        ethnicity: 'White',
        income: 'prefer-not-to-say'
      });
    });
  });
  
  it('送信中は送信ボタンが無効化されること', async () => {
    // Make saveConsentData slow to resolve
    (saveConsentData as any).mockImplementation(() => new Promise(resolve => {
      setTimeout(() => resolve({ success: true }), 100);
    }));
    
    render(<ConsentForm onConsent={mockOnConsent} />);
    
    // Fill required fields and submit
    fireEvent.click(screen.getByPlaceholderText('Select age group'));
    fireEvent.click(screen.getByText('25-34'));
    fireEvent.click(screen.getByLabelText('Female'));
    fireEvent.click(screen.getByText('I agree to participate in this research'));
    
    const submitButton = screen.getByRole('button', { name: /submit/i });
    fireEvent.click(submitButton);
    
    // Button should be disabled and show loading state
    expect(submitButton).toBeDisabled();
    
    // Wait for submission to complete
    await waitFor(() => {
      expect(mockOnConsent).toHaveBeenCalled();
    });
  });
}); 