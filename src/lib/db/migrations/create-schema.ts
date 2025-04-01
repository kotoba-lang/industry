import { sql } from 'drizzle-orm';
import { db } from '../index';

/**
 * spirit_in_physics スキーマを作成する
 */
export async function createSpiritInPhysicsSchema() {
  try {
    // スキーマが存在しない場合のみ作成
    await db.execute(sql`
      CREATE SCHEMA IF NOT EXISTS spirit_in_physics;
    `);
    console.log('spirit_in_physics schema created or already exists');
    return true;
  } catch (error) {
    console.error('Error creating schema:', error);
    return false;
  }
}

// マイグレーションを実行
if (require.main === module) {
  createSpiritInPhysicsSchema()
    .then(() => {
      console.log('Schema migration completed');
      process.exit(0);
    })
    .catch((error) => {
      console.error('Schema migration failed:', error);
      process.exit(1);
    });
} 