local std = import 'stdlib.jsonnet';

// Spirit-in-Physics 実験プロセスネットワーク Merkle DAG
// 各ノードはハッシュを持ち、依存関係を表現

{
  // メタデータ
  metadata: {
    name: 'Spirit-in-Physics Process Network',
    version: '1.0.0',
    description: '参加者実験データの収集・処理・分析・可視化パイプライン',
    created: std.time.now(),
    author: 'Junkawasaki',
  },

  // ノード定義 - 各ノードはハッシュと依存関係を持つ
  nodes: {

    // === データ収集層 (Data Collection Layer) ===

    // 参加者同意データソース
    consent_data_source: {
      id: 'consent_data_source',
      type: 'data_source',
      layer: 'collection',
      description: '参加者の同意情報と基本データ',
      format: 'jsonl',
      path: '.artifacts_cache/database.jsonl',
      schema: {
        participantId: 'string',
        signature: 'string',
        agreements: 'object',
        agreedAt: 'datetime',
      },
      hash: std.md5(std.toString(self)),
      dependencies: [],
    },

    // セッションデータソース
    session_data_source: {
      id: 'session_data_source',
      type: 'data_source',
      layer: 'collection',
      description: '実験セッションの詳細イベントデータ',
      format: 'json',
      path: '.artifacts_cache/{participantId}/session_data.json',
      schema: {
        participantId: 'string',
        events: 'array[SessionEvent]',
        wordResponses: 'array',
      },
      hash: std.md5(std.toString(self)),
      dependencies: [],
    },

    // ビデオファイルソース
    video_data_source: {
      id: 'video_data_source',
      type: 'data_source',
      layer: 'collection',
      description: '参加者の顔表情ビデオデータ',
      format: 'webm',
      path: '.artifacts_cache/{participantId}/*.webm',
      metadata: {
        codec: 'VP8/VP9',
        fps: 30,
        resolution: 'variable',
      },
      hash: std.md5(std.toString(self)),
      dependencies: [],
    },

    // === データ処理層 (Data Processing Layer) ===

    // データ読み込みプロセッサ
    data_loader: {
      id: 'data_loader',
      type: 'processor',
      layer: 'processing',
      description: 'ファイルシステムからのデータ読み込みと初期パース',
      input: ['consent_data_source', 'session_data_source', 'video_data_source'],
      output: ['parsed_participants', 'parsed_sessions', 'video_files'],
      processing_logic: {
        consent_parser: 'loadConsentDataFromDatabase()',
        session_parser: 'loadSessionData()',
        video_discovery: 'getParticipantDirectories()',
      },
      hash: std.md5(std.toString(self)),
      dependencies: ['consent_data_source', 'session_data_source', 'video_data_source'],
    },

    // 反応時間計算プロセッサ
    reaction_time_processor: {
      id: 'reaction_time_processor',
      type: 'processor',
      layer: 'processing',
      description: 'セッションイベントから反応時間を計算',
      input: ['parsed_sessions'],
      output: ['reaction_time_data'],
      processing_logic: {
        word_response_extraction: 'parseWordResponsesFromEvents()',
        reaction_time_calculation: 'timestamp_diff(word_displayed, speech_detected)',
        delayed_response_detection: 'check_response_window()',
      },
      hash: std.md5(std.toString(self)),
      dependencies: ['data_loader'],
    },

    // Hume AI感情分析プロセッサ
    hume_emotion_processor: {
      id: 'hume_emotion_processor',
      type: 'ai_processor',
      layer: 'processing',
      description: 'Hume AIを使用したビデオ感情分析',
      input: ['video_files'],
      output: ['emotion_analysis_results'],
      processing_logic: {
        face_analysis: 'HumeClient.expressionMeasurement.batch',
        emotion_scoring: 'FACS + descriptions',
        confidence_calculation: 'prediction_confidence',
        batch_processing: 'analyzeAllParticipantVideos()',
      },
      config: {
        api_key: 'HUME_API_KEY',
        secret_key: 'HUME_API',
        models: ['face.facs', 'face.descriptions'],
        rate_limit: '1 request/2 seconds',
      },
      hash: std.md5(std.toString(self)),
      dependencies: ['data_loader', 'video_data_source'],
    },

    // === 分析・集計層 (Analysis & Aggregation Layer) ===

    // 統計分析プロセッサ
    statistics_processor: {
      id: 'statistics_processor',
      type: 'analyzer',
      layer: 'analysis',
      description: '実験データの統計分析と集計',
      input: ['parsed_participants', 'reaction_time_data', 'emotion_analysis_results'],
      output: ['experiment_statistics', 'participant_metrics'],
      processing_logic: {
        completion_rate: 'participants_with_data / total_participants',
        average_reaction_time: 'mean(reaction_times)',
        emotion_distribution: 'aggregate_emotion_scores()',
        session_duration: 'calculate_session_times()',
      },
      hash: std.md5(std.toString(self)),
      dependencies: ['data_loader', 'reaction_time_processor', 'hume_emotion_processor'],
    },

    // 相関分析プロセッサ
    correlation_processor: {
      id: 'correlation_processor',
      type: 'analyzer',
      layer: 'analysis',
      description: '参加者特性と実験結果の相関分析',
      input: ['parsed_participants', 'reaction_time_data', 'emotion_analysis_results'],
      output: ['correlation_matrix', 'significant_findings'],
      processing_logic: {
        emotion_reaction_correlation: 'correlate(emotion_scores, reaction_times)',
        participant_demographics: 'analyze_demographic_patterns()',
        session_performance: 'analyze_performance_trends()',
      },
      hash: std.md5(std.toString(self)),
      dependencies: ['statistics_processor'],
    },

    // === 可視化層 (Visualization Layer) ===

    // ダッシュボード可視化プロセッサ
    dashboard_visualizer: {
      id: 'dashboard_visualizer',
      type: 'visualizer',
      layer: 'presentation',
      description: '管理ダッシュボード用のデータ可視化',
      input: ['experiment_statistics', 'participant_metrics', 'correlation_matrix'],
      output: ['dashboard_components', 'charts_data'],
      processing_logic: {
        participant_overview: 'ParticipantOverview.tsx',
        analytics_charts: 'ExperimentAnalytics.tsx',
        timeline_view: 'SessionTimeline.tsx',
        emotion_radar: 'RadarChart(emotion_scores)',
      },
      hash: std.md5(std.toString(self)),
      dependencies: ['statistics_processor', 'correlation_processor'],
    },

    // レポート生成プロセッサ
    report_generator: {
      id: 'report_generator',
      type: 'exporter',
      layer: 'presentation',
      description: '実験結果のレポート生成とエクスポート',
      input: ['experiment_statistics', 'correlation_matrix', 'significant_findings'],
      output: ['pdf_reports', 'csv_exports', 'json_datasets'],
      processing_logic: {
        summary_report: 'generate_executive_summary()',
        detailed_analysis: 'create_detailed_report()',
        data_export: 'ExportTools.tsx',
      },
      hash: std.md5(std.toString(self)),
      dependencies: ['statistics_processor', 'correlation_processor'],
    },

    // === ストレージ・永続化層 (Storage & Persistence Layer) ===

    // 分析結果保存プロセッサ
    results_persistence: {
      id: 'results_persistence',
      type: 'storage',
      layer: 'persistence',
      description: '分析結果の永続化とキャッシュ',
      input: ['emotion_analysis_results', 'experiment_statistics', 'correlation_matrix'],
      output: ['cached_results', 'exported_datasets'],
      processing_logic: {
        emotion_cache: 'emotion_analysis.json',
        stats_cache: 'statistics_cache.json',
        result_backup: 'timestamped_backups/',
      },
      hash: std.md5(std.toString(self)),
      dependencies: ['hume_emotion_processor', 'statistics_processor', 'correlation_processor'],
    },

    // === 品質管理・検証層 (Quality Assurance Layer) ===

    // データ検証プロセッサ
    data_validator: {
      id: 'data_validator',
      type: 'validator',
      layer: 'qa',
      description: 'データ品質と整合性の検証',
      input: ['parsed_participants', 'reaction_time_data', 'emotion_analysis_results'],
      output: ['validation_reports', 'data_quality_metrics'],
      processing_logic: {
        completeness_check: 'check_required_fields()',
        consistency_validation: 'validate_data_consistency()',
        outlier_detection: 'identify_statistical_outliers()',
      },
      hash: std.md5(std.toString(self)),
      dependencies: ['data_loader', 'reaction_time_processor', 'hume_emotion_processor'],
    },

  },

  // エッジ定義 - ノード間の依存関係とデータフロー
  edges: [
    // データ収集 → 処理
    {
      from: 'consent_data_source',
      to: 'data_loader',
      type: 'data_flow',
      description: '同意データ読み込み',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'session_data_source',
      to: 'data_loader',
      type: 'data_flow',
      description: 'セッションデータ読み込み',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'video_data_source',
      to: 'data_loader',
      type: 'data_flow',
      description: 'ビデオファイル検出',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'video_data_source',
      to: 'hume_emotion_processor',
      type: 'data_flow',
      description: '感情分析用ビデオ供給',
      hash: std.md5(std.toString(self)),
    },

    // 処理 → 分析
    {
      from: 'data_loader',
      to: 'reaction_time_processor',
      type: 'data_flow',
      description: '反応時間計算',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'data_loader',
      to: 'statistics_processor',
      type: 'data_flow',
      description: '参加者統計生成',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'reaction_time_processor',
      to: 'statistics_processor',
      type: 'data_flow',
      description: '反応時間統計',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'hume_emotion_processor',
      to: 'statistics_processor',
      type: 'data_flow',
      description: '感情分析結果統計',
      hash: std.md5(std.toString(self)),
    },

    // 分析 → 相関分析
    {
      from: 'statistics_processor',
      to: 'correlation_processor',
      type: 'data_flow',
      description: '統計データ相関分析',
      hash: std.md5(std.toString(self)),
    },

    // 分析 → 可視化
    {
      from: 'statistics_processor',
      to: 'dashboard_visualizer',
      type: 'data_flow',
      description: '統計データ可視化',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'correlation_processor',
      to: 'dashboard_visualizer',
      type: 'data_flow',
      description: '相関分析結果可視化',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'statistics_processor',
      to: 'report_generator',
      type: 'data_flow',
      description: '統計レポート生成',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'correlation_processor',
      to: 'report_generator',
      type: 'data_flow',
      description: '相関分析レポート',
      hash: std.md5(std.toString(self)),
    },

    // 永続化
    {
      from: 'hume_emotion_processor',
      to: 'results_persistence',
      type: 'data_flow',
      description: '感情分析結果保存',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'statistics_processor',
      to: 'results_persistence',
      type: 'data_flow',
      description: '統計結果保存',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'correlation_processor',
      to: 'results_persistence',
      type: 'data_flow',
      description: '相関分析結果保存',
      hash: std.md5(std.toString(self)),
    },

    // 品質検証
    {
      from: 'data_loader',
      to: 'data_validator',
      type: 'validation_flow',
      description: 'データ品質検証',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'reaction_time_processor',
      to: 'data_validator',
      type: 'validation_flow',
      description: '反応時間データ検証',
      hash: std.md5(std.toString(self)),
    },
    {
      from: 'hume_emotion_processor',
      to: 'data_validator',
      type: 'validation_flow',
      description: '感情分析結果検証',
      hash: std.md5(std.toString(self)),
    },
  ],

  // 実行パイプライン定義
  pipelines: {

    // 完全な実験分析パイプライン
    full_analysis_pipeline: {
      id: 'full_analysis_pipeline',
      description: 'データ収集からレポート生成までの完全パイプライン',
      stages: [
        'data_collection',
        'data_processing',
        'emotion_analysis',
        'statistical_analysis',
        'correlation_analysis',
        'visualization',
        'reporting',
        'persistence',
      ],
      entry_points: ['consent_data_source', 'session_data_source', 'video_data_source'],
      exit_points: ['dashboard_visualizer', 'report_generator', 'results_persistence'],
      hash: std.md5(std.toString(self)),
    },

    // 感情分析専用パイプライン
    emotion_analysis_pipeline: {
      id: 'emotion_analysis_pipeline',
      description: 'ビデオ感情分析に特化したパイプライン',
      stages: [
        'video_collection',
        'emotion_processing',
        'emotion_visualization',
        'emotion_persistence',
      ],
      entry_points: ['video_data_source'],
      exit_points: ['dashboard_visualizer', 'results_persistence'],
      hash: std.md5(std.toString(self)),
    },

    // リアルタイム分析パイプライン
    realtime_analysis_pipeline: {
      id: 'realtime_analysis_pipeline',
      description: '新しい参加者データに対するリアルタイム分析',
      stages: [
        'data_collection',
        'data_processing',
        'statistical_analysis',
        'dashboard_update',
      ],
      entry_points: ['consent_data_source', 'session_data_source'],
      exit_points: ['dashboard_visualizer'],
      hash: std.md5(std.toString(self)),
    },

  },

  // ステージ定義
  stages: {
    data_collection: {
      name: 'データ収集',
      description: '参加者データ、セッションデータ、ビデオファイルの収集',
      nodes: ['consent_data_source', 'session_data_source', 'video_data_source'],
      order: 1,
    },
    data_processing: {
      name: 'データ処理',
      description: '生データの読み込み、パース、初期処理',
      nodes: ['data_loader', 'reaction_time_processor'],
      order: 2,
    },
    emotion_analysis: {
      name: '感情分析',
      description: 'Hume AIを使用したビデオ感情分析',
      nodes: ['hume_emotion_processor'],
      order: 3,
    },
    statistical_analysis: {
      name: '統計分析',
      description: '実験データの統計分析と集計',
      nodes: ['statistics_processor'],
      order: 4,
    },
    correlation_analysis: {
      name: '相関分析',
      description: '参加者特性と実験結果の相関分析',
      nodes: ['correlation_processor'],
      order: 5,
    },
    visualization: {
      name: '可視化',
      description: '分析結果のグラフ・チャート生成',
      nodes: ['dashboard_visualizer'],
      order: 6,
    },
    reporting: {
      name: 'レポート生成',
      description: '実験結果のレポートとエクスポート',
      nodes: ['report_generator'],
      order: 7,
    },
    persistence: {
      name: 'データ永続化',
      description: '分析結果の保存とキャッシュ',
      nodes: ['results_persistence'],
      order: 8,
    },
    validation: {
      name: '品質検証',
      description: 'データ品質と整合性の検証',
      nodes: ['data_validator'],
      order: 9,
    },
  },

  // ワークフロー定義
  workflows: {

    // 参加者新規登録ワークフロー
    participant_registration: {
      id: 'participant_registration',
      description: '新規参加者の登録と初期データ処理',
      steps: [
        {
          step: 1,
          name: '同意データ収集',
          node: 'consent_data_source',
          action: 'store_consent_data',
        },
        {
          step: 2,
          name: 'データ読み込み',
          node: 'data_loader',
          action: 'parse_participant_data',
        },
        {
          step: 3,
          name: '統計更新',
          node: 'statistics_processor',
          action: 'update_participant_stats',
        },
        {
          step: 4,
          name: 'ダッシュボード更新',
          node: 'dashboard_visualizer',
          action: 'refresh_participant_overview',
        },
      ],
      hash: std.md5(std.toString(self)),
    },

    // 実験セッション完了ワークフロー
    session_completion: {
      id: 'session_completion',
      description: '実験セッション完了時のデータ処理',
      steps: [
        {
          step: 1,
          name: 'セッションデータ収集',
          node: 'session_data_source',
          action: 'store_session_events',
        },
        {
          step: 2,
          name: '反応時間計算',
          node: 'reaction_time_processor',
          action: 'calculate_reaction_times',
        },
        {
          step: 3,
          name: '統計更新',
          node: 'statistics_processor',
          action: 'update_session_stats',
        },
        {
          step: 4,
          name: 'ダッシュボード更新',
          node: 'dashboard_visualizer',
          action: 'refresh_analytics',
        },
      ],
      hash: std.md5(std.toString(self)),
    },

    // 感情分析ワークフロー
    emotion_analysis_workflow: {
      id: 'emotion_analysis_workflow',
      description: 'ビデオ感情分析の実行と結果処理',
      steps: [
        {
          step: 1,
          name: 'ビデオファイル収集',
          node: 'video_data_source',
          action: 'discover_video_files',
        },
        {
          step: 2,
          name: '感情分析実行',
          node: 'hume_emotion_processor',
          action: 'analyze_emotions_batch',
        },
        {
          step: 3,
          name: '結果保存',
          node: 'results_persistence',
          action: 'save_emotion_results',
        },
        {
          step: 4,
          name: '可視化更新',
          node: 'dashboard_visualizer',
          action: 'update_emotion_charts',
        },
      ],
      hash: std.md5(std.toString(self)),
    },

  },

  // システム設定
  configuration: {
    data_retention: {
      raw_data: 'indefinite',
      processed_data: '1 year',
      analysis_results: '2 years',
      logs: '6 months',
    },
    performance: {
      max_concurrent_processes: 5,
      api_rate_limits: {
        hume_api: '1 req/2s',
        database_reads: '100 req/s',
      },
      cache_ttl: {
        statistics: '5 minutes',
        emotion_analysis: '1 hour',
      },
    },
    monitoring: {
      health_checks: ['data_integrity', 'api_connectivity', 'storage_capacity'],
      alerts: ['processing_failures', 'data_corruption', 'api_rate_limits'],
      metrics: ['processing_time', 'success_rate', 'data_volume'],
    },
  },

  // ハッシュ計算関数
  computeGlobalHash()::
    local allNodes = std.join('', [$.nodes[n].hash for n in std.objectFields($.nodes)]);
    local allEdges = std.join('', [e.hash for e in $.edges]);
    std.md5(allNodes + allEdges),

  // グローバルハッシュ
  globalHash: self.computeGlobalHash(),
}
