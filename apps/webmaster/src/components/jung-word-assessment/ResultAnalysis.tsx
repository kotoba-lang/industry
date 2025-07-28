"use client";

import { useState } from 'react';
import { z } from 'zod';

// Schema for a single word response
const WordResponseSchema = z.object({
  stimulus: z.string(),
  response: z.string(),
  reactionTimeMs: z.number(),
  isDelayed: z.boolean(),
});

// Schema for the complete test results
const TestResultsSchema = z.object({
  responses: z.array(WordResponseSchema),
  averageReactionTimeMs: z.number(),
  delayedResponseCount: z.number(),
  completedAt: z.date(),
});

type WordResponse = z.infer<typeof WordResponseSchema>;
type TestResults = z.infer<typeof TestResultsSchema>;

// Categories for potential complexes based on Jung's observations
type ComplexCategory = {
  name: string;
  description: string;
  relatedWords: string[];
};

const complexCategories: ComplexCategory[] = [
  {
    name: 'Family/Relationship',
    description: 'Potential complexes related to family dynamics or relationships',
    relatedWords: ['mother', 'father', 'family', 'child', 'to marry', 'friend', 'friendly', 'to quarrel', 'to kiss']
  },
  {
    name: 'Emotional',
    description: 'Emotional reactions that may indicate hidden feelings',
    relatedWords: ['happy', 'sad', 'angry', 'anxious', 'contented', 'to fear', 'happiness', 'despise', 'dear', 'ridicule']
  },
  {
    name: 'Ethical/Moral',
    description: 'Complexes related to moral or ethical dilemmas',
    relatedWords: ['to sin', 'to pray', 'false', 'unjust', 'rich', 'to pay', 'expensive', 'pride']
  },
  {
    name: 'Self-concept',
    description: 'Issues related to self-image and identity',
    relatedWords: ['to choose', 'pride', 'head', 'finger', 'clean', 'sick', 'new', 'to fall']
  }
];

interface ResultAnalysisProps {
  results: TestResults;
}

export default function ResultAnalysis({ results }: ResultAnalysisProps) {
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  
  // Find potentially significant responses (delayed reactions)
  const delayedResponses = results.responses.filter(r => r.isDelayed);
  
  // Calculate stats
  const totalResponseTime = results.responses.reduce((sum, r) => sum + r.reactionTimeMs, 0);
  const averageResponseTime = Math.round(totalResponseTime / results.responses.length);
  const fastestResponse = Math.min(...results.responses.map(r => r.reactionTimeMs));
  const slowestResponse = Math.max(...results.responses.map(r => r.reactionTimeMs));
  
  // Find responses by category
  const getCategoryResponses = (category: ComplexCategory) => {
    return results.responses.filter(response => 
      category.relatedWords.includes(response.stimulus.toLowerCase())
    );
  };
  
  // Get the selected category data
  const selectedCategoryData = selectedCategory
    ? complexCategories.find(cat => cat.name === selectedCategory)
    : null;
    
  const selectedCategoryResponses = selectedCategoryData
    ? getCategoryResponses(selectedCategoryData)
    : [];
  
  // Generate simple insights
  const getInsights = () => {
    const insights = [];
    
    if (delayedResponses.length > results.responses.length * 0.2) {
      insights.push("You had a high number of delayed responses, which may indicate significant emotional complexes according to Jung's theory.");
    }
    
    if (delayedResponses.length === 0) {
      insights.push("You had no delayed responses, which may indicate minimal emotional complexes or good emotional regulation.");
    }
    
    // Find categories with the most delayed responses
    const categoriesWithDelays = complexCategories.map(category => {
      const categoryResponses = getCategoryResponses(category);
      const delayedCategoryResponses = categoryResponses.filter(r => r.isDelayed);
      return {
        category,
        delayedCount: delayedCategoryResponses.length,
        totalCount: categoryResponses.length
      };
    }).filter(item => item.delayedCount > 0)
      .sort((a, b) => b.delayedCount / b.totalCount - a.delayedCount / a.totalCount);
    
    if (categoriesWithDelays.length > 0) {
      const topCategory = categoriesWithDelays[0];
      if (topCategory.delayedCount / topCategory.totalCount > 0.3) {
        insights.push(`You had a significant number of delayed responses in the "${topCategory.category.name}" category, which may indicate a complex in this area.`);
      }
    }
    
    return insights;
  };
  
  return (
    <div className="max-w-2xl mx-auto p-6 bg-white rounded-lg shadow-md">
      <h2 className="text-2xl font-bold mb-6 text-center">Analysis of Your Word Association Test</h2>
      
      <div className="mb-8">
        <h3 className="text-xl font-semibold mb-4">Statistical Overview</h3>
        <div className="grid grid-cols-2 gap-4">
          <div className="bg-gray-50 p-4 rounded-md">
            <p className="text-sm text-gray-500">Average Response Time</p>
            <p className="text-2xl font-medium">{averageResponseTime} ms</p>
          </div>
          <div className="bg-gray-50 p-4 rounded-md">
            <p className="text-sm text-gray-500">Delayed Responses</p>
            <p className="text-2xl font-medium">{delayedResponses.length} / {results.responses.length}</p>
          </div>
          <div className="bg-gray-50 p-4 rounded-md">
            <p className="text-sm text-gray-500">Fastest Response</p>
            <p className="text-2xl font-medium">{fastestResponse} ms</p>
          </div>
          <div className="bg-gray-50 p-4 rounded-md">
            <p className="text-sm text-gray-500">Slowest Response</p>
            <p className="text-2xl font-medium">{slowestResponse} ms</p>
          </div>
        </div>
      </div>
      
      <div className="mb-8">
        <h3 className="text-xl font-semibold mb-4">Jungian Insights</h3>
        <div className="bg-blue-50 p-4 rounded-md mb-4">
          <p className="text-sm leading-relaxed">
            According to Jung's theory, delayed responses (over 2 seconds) may indicate 
            emotional complexes in the subconscious mind. These are areas where you may have 
            strong emotional associations or unresolved issues.
          </p>
        </div>
        
        <div className="space-y-2">
          {getInsights().map((insight, index) => (
            <div key={index} className="bg-yellow-50 p-3 rounded-md">
              <p>{insight}</p>
            </div>
          ))}
          
          {getInsights().length === 0 && (
            <p className="text-gray-500 italic">Not enough data to generate insights.</p>
          )}
        </div>
      </div>
      
      <div className="mb-8">
        <h3 className="text-xl font-semibold mb-4">Explore by Category</h3>
        <div className="flex flex-wrap gap-2 mb-4">
          {complexCategories.map((category) => (
            <button
              key={category.name}
              onClick={() => setSelectedCategory(selectedCategory === category.name ? null : category.name)}
              className={`px-3 py-1 rounded-full text-sm ${
                selectedCategory === category.name
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-200 text-gray-800 hover:bg-gray-300'
              }`}
            >
              {category.name}
            </button>
          ))}
        </div>
        
        {selectedCategoryData && (
          <div className="bg-gray-50 p-4 rounded-md">
            <h4 className="font-medium mb-2">{selectedCategoryData.name}</h4>
            <p className="text-sm text-gray-600 mb-4">{selectedCategoryData.description}</p>
            
            {selectedCategoryResponses.length > 0 ? (
              <div>
                <p className="text-sm font-medium mb-2">Your responses in this category:</p>
                <table className="w-full text-sm">
                  <thead className="bg-gray-100">
                    <tr>
                      <th className="text-left px-3 py-2">Stimulus</th>
                      <th className="text-left px-3 py-2">Response</th>
                      <th className="text-left px-3 py-2">Time (ms)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {selectedCategoryResponses.map((response, index) => (
                      <tr key={index} className={response.isDelayed ? "bg-yellow-50" : ""}>
                        <td className="px-3 py-2">{response.stimulus}</td>
                        <td className="px-3 py-2">{response.response}</td>
                        <td className={`px-3 py-2 ${response.isDelayed ? "text-red-600 font-medium" : ""}`}>
                          {response.reactionTimeMs}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-gray-500 italic">No responses found in this category.</p>
            )}
          </div>
        )}
      </div>
      
      <div>
        <h3 className="text-xl font-semibold mb-4">Important Note</h3>
        <div className="bg-gray-50 p-4 rounded-md text-sm text-gray-600">
          <p className="mb-2">
            This analysis is based on Carl Jung's word association theories from 1910 
            and should be considered primarily for educational and entertainment purposes.
          </p>
          <p>
            While Jung's theories have been influential in psychology, modern approaches
            to psychological assessment are typically more comprehensive. This test is not
            intended to diagnose any psychological condition.
          </p>
        </div>
      </div>
    </div>
  );
} 