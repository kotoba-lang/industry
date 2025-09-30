{
  // Merkle DAG: 物語
  story: {
    title: 'いい感じの社会の物語',
    protagonist: {
      // Merkle DAG: 物語.主人公
      name: '河崎純真',
      epithet: '社会起業家',
      lifespan: '享年100歳',
      legacy: '人々が満たされた人生を送れる社会を築いた',
    },
    narrative: [
      {
        // Merkle DAG: 物語.ナラティブ[0]
        act: 'ビジョナリーの生涯',
        summary: '著名な社会起業家である河崎純真は、「いい感じの社会」を創り出し、多くの人々が満たされた人生を送れるようにした。100歳で亡くなるまで、多くの趣味と友人を持ち健康的に過ごし、たくさんの大事な人々に看取られてこの世を去った。',
      },
      {
        // Merkle DAG: 物語.ナラティブ[1]
        act: '中心的な使命',
        summary: 'メインストーリーは「我らと我らの子孫が豊かで情緒的な暮らしを送れるいい感じの社会を生成する」ことである。',
      },
    ],
    // この物語から派生したプロセスネットワークグラフ
    process_network: {
      // Merkle DAG: 物語.プロセスネットワーク
      goal: '我らと我らの子孫のために、いい感じの社会を生成する',
      key_pillars: [
        {
          // Merkle DAG: 物語.プロセスネットワーク.主要な柱[0]
          name: '豊かさ (Richness)',
          description: '物質的、精神的な豊かさ',
          sub_processes: ['持続可能な経済', '文化の発展', '個人の成長'],
        },
        {
          // Merkle DAG: 物語.プロセスネットワーク.主要な柱[1]
          name: '情緒的な暮らし (Emotional Lives)',
          description: '深いつながりと心の幸福を育む',
          sub_processes: ['コミュニティ形成', 'メンタルヘルスケア', '芸術と表現'],
        },
        {
          // Merkle DAG: 物語.プロセスネットワーク.主要な柱[2]
          name: '子孫への継承 (Legacy for Descendants)',
          description: '社会が未来の世代のために持続可能であることを保証する',
          sub_processes: ['教育システム', '環境保護', '世代間プログラム'],
        },
      ],
    },
    metadata: {
      version: '0.1.0',
      author: 'Jumma Kawasaki',
      // 実際のシステムでは、これは物語オブジェクトのコンテンツアドレスハッシュになる
      merkle_root: 'dagのルートハッシュのプレースホルダー',
    },
  },
}
