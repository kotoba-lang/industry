'use client';

import React, { useState } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Download,
  FileText,
  Database,
  FileSpreadsheet,
  Settings,
  CheckCircle,
  AlertCircle
} from 'lucide-react';

export function ExportTools() {
  const [exportFormat, setExportFormat] = useState('json');
  const [selectedDataTypes, setSelectedDataTypes] = useState({
    participants: true,
    sessions: true,
    reactions: true,
    emotions: true,
    analytics: false
  });
  const [dateRange, setDateRange] = useState('all');
  const [isExporting, setIsExporting] = useState(false);
  const [exportStatus, setExportStatus] = useState<'idle' | 'success' | 'error'>('idle');

  const handleDataTypeChange = (dataType: string, checked: boolean) => {
    setSelectedDataTypes(prev => ({
      ...prev,
      [dataType]: checked
    }));
  };

  const handleExport = async () => {
    setIsExporting(true);
    setExportStatus('idle');

    try {
      // Collect selected data types
      const dataToExport = [];
      const selectedTypes = Object.entries(selectedDataTypes)
        .filter(([, selected]) => selected)
        .map(([type]) => type);

      // Fetch data for each selected type
      for (const type of selectedTypes) {
        try {
          const response = await fetch(`/api/admin/experimental-data?type=${type}`);
          const result = await response.json();
          if (result.success) {
            dataToExport.push({
              type,
              data: result.data
            });
          }
        } catch (error) {
          console.error(`Error fetching ${type} data:`, error);
        }
      }

      // Create export data object
      const exportData = {
        exportDate: new Date().toISOString(),
        dateRange,
        dataTypes: selectedTypes,
        data: dataToExport
      };

      let blob;
      let filename;

      switch (exportFormat) {
        case 'json':
          blob = new Blob([JSON.stringify(exportData, null, 2)], {
            type: 'application/json'
          });
          filename = `spirit-in-physics-export-${new Date().toISOString().split('T')[0]}.json`;
          break;

        case 'csv':
          // Convert to CSV format (simplified)
          const csvData = convertToCSV(exportData);
          blob = new Blob([csvData], { type: 'text/csv' });
          filename = `spirit-in-physics-export-${new Date().toISOString().split('T')[0]}.csv`;
          break;

        default:
          throw new Error('Unsupported export format');
      }

      // Download file
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      setExportStatus('success');
    } catch (error) {
      console.error('Export error:', error);
      setExportStatus('error');
    } finally {
      setIsExporting(false);
    }
  };

  const convertToCSV = (data: { data: Array<{ type: string; data: unknown }> }): string => {
    // Simplified CSV conversion - in production, you'd want a more robust solution
    const headers = ['Type', 'Data'];
    const rows = data.data.map((item) =>
      [item.type, JSON.stringify(item.data)]
    );

    return [headers, ...rows]
      .map(row => row.map(field => `"${field}"`).join(','))
      .join('\n');
  };

  const exportOptions = [
    {
      id: 'json',
      name: 'JSON',
      description: '構造化されたJSON形式でデータをエクスポート',
      icon: FileText
    },
    {
      id: 'csv',
      name: 'CSV',
      description: '表形式のCSV形式でデータをエクスポート',
      icon: FileSpreadsheet
    }
  ];

  const dataTypeOptions = [
    {
      id: 'participants',
      name: '参加者データ',
      description: '参加者の同意情報とセッション状況',
      count: '9件' // database.jsonlから取得した実際の参加者数
    },
    {
      id: 'sessions',
      name: 'セッションデータ',
      description: '実験セッションのイベント詳細',
      count: '11件' // participantディレクトリの数
    },
    {
      id: 'reactions',
      name: '反応時間データ',
      description: 'セッションイベントから解析した反応時間',
      count: '動的'
    },
    {
      id: 'emotions',
      name: '感情データ',
      description: '実験中の感情分析結果（現在利用不可）',
      count: '0件'
    },
    {
      id: 'analytics',
      name: '分析データ',
      description: '統計分析結果と集計データ',
      count: '1件'
    }
  ];

  return (
    <div className="space-y-6">
      {/* Export Configuration */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <Settings className="h-5 w-5" />
            <span>エクスポート設定</span>
          </CardTitle>
          <CardDescription>
            エクスポートするデータ形式と範囲を設定してください
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* Export Format */}
          <div>
            <label className="block text-sm font-medium mb-3">エクスポート形式</label>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {exportOptions.map((option) => {
                const Icon = option.icon;
                return (
                  <div
                    key={option.id}
                    className={`p-4 border rounded-lg cursor-pointer transition-colors ${
                      exportFormat === option.id
                        ? 'border-blue-500 bg-blue-50'
                        : 'border-gray-200 hover:border-gray-300'
                    }`}
                    onClick={() => setExportFormat(option.id)}
                  >
                    <div className="flex items-center space-x-3">
                      <Icon className="h-5 w-5 text-gray-600" />
                      <div>
                        <h3 className="font-medium">{option.name}</h3>
                        <p className="text-sm text-gray-600">{option.description}</p>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Date Range */}
          <div>
            <label className="block text-sm font-medium mb-2">期間指定</label>
            <Select value={dateRange} onValueChange={setDateRange}>
              <SelectTrigger className="w-full md:w-64">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全ての期間</SelectItem>
                <SelectItem value="last-week">過去1週間</SelectItem>
                <SelectItem value="last-month">過去1ヶ月</SelectItem>
                <SelectItem value="last-quarter">過去3ヶ月</SelectItem>
                <SelectItem value="custom">カスタム期間</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </CardContent>
      </Card>

      {/* Data Type Selection */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <Database className="h-5 w-5" />
            <span>データタイプ選択</span>
          </CardTitle>
          <CardDescription>
            エクスポートするデータの種類を選択してください
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-3">
            {dataTypeOptions.map((option) => (
              <div key={option.id} className="flex items-center space-x-3 p-3 border rounded-lg">
                <Checkbox
                  id={option.id}
                  checked={selectedDataTypes[option.id as keyof typeof selectedDataTypes]}
                  onCheckedChange={(checked) =>
                    handleDataTypeChange(option.id, checked as boolean)
                  }
                />
                <div className="flex-1">
                  <label
                    htmlFor={option.id}
                    className="flex items-center justify-between cursor-pointer"
                  >
                    <div>
                      <h3 className="font-medium">{option.name}</h3>
                      <p className="text-sm text-gray-600">{option.description}</p>
                    </div>
                    <Badge variant="secondary">{option.count}</Badge>
                  </label>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Export Actions */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <Download className="h-5 w-5" />
            <span>エクスポート実行</span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {/* Export Summary */}
            <div className="bg-gray-50 p-4 rounded-lg">
              <h4 className="font-medium mb-2">エクスポート内容の概要</h4>
              <div className="text-sm text-gray-600 space-y-1">
                <p>• 形式: {exportOptions.find(opt => opt.id === exportFormat)?.name}</p>
                <p>• 期間: {dateRange === 'all' ? '全て' : dateRange}</p>
                <p>• 選択されたデータタイプ: {
                  Object.entries(selectedDataTypes)
                    .filter(([, selected]) => selected)
                    .map(([type]) => dataTypeOptions.find(opt => opt.id === type)?.name)
                    .join(', ')
                }</p>
              </div>
            </div>

            {/* Status Message */}
            {exportStatus === 'success' && (
              <div className="flex items-center space-x-2 p-3 bg-green-50 border border-green-200 rounded-lg">
                <CheckCircle className="h-5 w-5 text-green-600" />
                <span className="text-green-800">エクスポートが完了しました</span>
              </div>
            )}

            {exportStatus === 'error' && (
              <div className="flex items-center space-x-2 p-3 bg-red-50 border border-red-200 rounded-lg">
                <AlertCircle className="h-5 w-5 text-red-600" />
                <span className="text-red-800">エクスポート中にエラーが発生しました</span>
              </div>
            )}

            {/* Export Button */}
            <Button
              onClick={handleExport}
              disabled={isExporting || !Object.values(selectedDataTypes).some(Boolean)}
              className="w-full md:w-auto"
            >
              {isExporting ? (
                <>
                  <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white mr-2"></div>
                  エクスポート中...
                </>
              ) : (
                <>
                  <Download className="h-4 w-4 mr-2" />
                  データをエクスポート
                </>
              )}
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Export History */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <FileText className="h-5 w-5" />
            <span>エクスポート履歴</span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="text-center py-8 text-gray-500">
            エクスポート履歴はここに表示されます
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
