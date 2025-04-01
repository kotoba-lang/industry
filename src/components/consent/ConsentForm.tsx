"use client";

import React, { useState } from 'react';
import { Button } from '../ui/button';
import { Checkbox } from '../ui/checkbox';

interface ConsentFormProps {
  onConsent: () => void;
}

export default function ConsentForm({ onConsent }: ConsentFormProps) {
  const [consented, setConsented] = useState(false);
  const [showFullConsent, setShowFullConsent] = useState(false);
  
  return (
    <div className="max-w-3xl mx-auto p-6 bg-white rounded-lg shadow-md border border-gray-200">
      <h2 className="text-2xl font-bold mb-4">Research Participation Consent</h2>
      
      <div className="mb-6">z
        <p className="mb-4">This Spirit in Physics (Jung's Word Association Embedding Test) is conducted for research purposes. Please read the following consent information before proceeding.</p>
        
        <button 
          onClick={() => setShowFullConsent(!showFullConsent)}
          className="text-blue-600 hover:underline mb-4"
        >
          {showFullConsent ? 'Collapse Consent Form' : 'Read Full Consent Form'}
        </button>
        
        {showFullConsent && (
          <div className="p-4 bg-gray-50 rounded-md mb-4 max-h-96 overflow-y-auto text-sm">
            <h3 className="font-bold mb-2">Research Purpose</h3>
            <p className="mb-4">
              This research aims to investigate the relationship between language responses and psychological processes using Spirit in Physics (Jung's Word Association Embedding Test).
              In this test, we record your immediate reactions to stimulus words.
            </p>
            
            <h3 className="font-bold mb-2">Research Procedure</h3>
            <p className="mb-4">
              In this research, a series of words will be presented, and you will be asked to respond with the first word that comes to mind for each.
              Reaction times will also be recorded. The test takes approximately 15-20 minutes to complete.
            </p>
            
            <h3 className="font-bold mb-2">Potential Risks and Discomfort</h3>
            <p className="mb-4">
              There are no physical risks associated with participating in this research. However, some stimulus words may evoke personal emotions or memories.
              If you feel uncomfortable, you may discontinue the test at any time.
            </p>
            
            <h3 className="font-bold mb-2">Benefits</h3>
            <p className="mb-4">
              There may be no direct benefits from participating in this research, but the test results may help deepen your self-understanding.
              Additionally, you will be contributing to the advancement of psychological research.
            </p>
            
            <h3 className="font-bold mb-2">Confidentiality</h3>
            <p className="mb-4">
              All data collected will be anonymized and strictly protected. Your personal information will not be identified when the research results are published.
              Data will be stored on secure servers and will not be used for purposes other than research.
            </p>
            
            <h3 className="font-bold mb-2">Voluntary Participation</h3>
            <p className="mb-4">
              Participation in this research is completely voluntary. You may withdraw at any time without explaining your reasons.
              Refusing or discontinuing participation will not result in any disadvantages.
            </p>
            
            <h3 className="font-bold mb-2">Contact Information</h3>
            <p className="mb-4">
              If you have questions or concerns about this research, please contact the research supervisor (contact@research-example.com).
              For questions about your rights as a research participant, you may contact the ethics committee (ethics@research-example.com).
            </p>
          </div>
        )}
      </div>
      
      <div className="space-y-4 mb-6">
        <div className="flex items-start">
          <Checkbox 
            id="consent1" 
            checked={consented} 
            onCheckedChange={(checked: boolean) => setConsented(checked)} 
            className="mt-1"
          />
          <label htmlFor="consent1" className="ml-2 text-sm">
            I have read and understood the above information. I have had the opportunity to ask questions and have received satisfactory answers to my questions. I voluntarily agree to participate in this research. I understand that I have the right to withdraw at any time.
          </label>
        </div>
      </div>
      
      <div className="flex justify-end">
        <Button 
          onClick={onConsent} 
          disabled={!consented}
          className="px-6"
        >
          Consent and Continue
        </Button>
      </div>
      
      <div className="mt-4 text-xs text-gray-500">
        <p>This consent process complies with ICH-GCP (International Conference on Harmonisation - Good Clinical Practice) standards.</p>
        <p>Approval number: STUDY-2023-001 | Approval date: January 15, 2023</p>
      </div>
    </div>
  );
} 