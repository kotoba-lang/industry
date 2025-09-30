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
      {
        // Merkle DAG: 物語.ナラティブ[2]
        act: 'Spirit in Physics',
        summary: '医学博士である河崎純真が、エホバの証人、科学哲学、仏教、アヤワスカ、コンピューターの経験を通して、クリスチャンのアンタッチャブルな「spirit」を可視化・提供可能にし、神と人、霊と人を統合する試み。これは @spirit-in-physics/ プロジェクトで探求される。',
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
        {
          // Merkle DAG: 物語.プロセスネットワーク.主要な柱[3]
          name: '霊性の統合 (Spiritual Integration)',
          description: '科学と精神世界を統合し、人間性の全体的な理解を深める',
          sub_processes: ['Spirit in Physicsプロジェクト', '科学的霊性研究', '統合的実践コミュニティ'],
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
