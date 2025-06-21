-- Supabase Storage Policies for Git LFS
-- Project: pxsuqemlayhnmcxuiigk
-- Bucket: lfs-storage

-- 1. Create the lfs-storage bucket (if not exists)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'lfs-storage',
    'lfs-storage',
    false,
    104857600, -- 100MB limit per file
    ARRAY['application/octet-stream', 'text/plain', 'application/json']
) ON CONFLICT (id) DO NOTHING;

-- 2. Enable RLS for storage.objects
ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;

-- 3. Policy for authenticated users to insert LFS objects
CREATE POLICY "Allow authenticated users to upload LFS objects" ON storage.objects
FOR INSERT WITH CHECK (
    bucket_id = 'lfs-storage' 
    AND auth.role() = 'authenticated'
);

-- 4. Policy for authenticated users to select LFS objects
CREATE POLICY "Allow authenticated users to read LFS objects" ON storage.objects
FOR SELECT USING (
    bucket_id = 'lfs-storage' 
    AND auth.role() = 'authenticated'
);

-- 5. Policy for service role to have full access
CREATE POLICY "Allow service role full access to LFS objects" ON storage.objects
FOR ALL USING (
    bucket_id = 'lfs-storage' 
    AND auth.role() = 'service_role'
);

-- 6. Policy for anonymous users to read public LFS objects (optional)
CREATE POLICY "Allow anonymous users to read public LFS objects" ON storage.objects
FOR SELECT USING (
    bucket_id = 'lfs-storage' 
    AND auth.role() = 'anon'
);

-- 7. Grant necessary permissions to authenticated role
GRANT SELECT, INSERT ON storage.objects TO authenticated;
GRANT SELECT ON storage.buckets TO authenticated;

-- 8. Grant necessary permissions to anon role
GRANT SELECT ON storage.objects TO anon;
GRANT SELECT ON storage.buckets TO anon;

-- 9. Create a view for LFS batch API compatibility
CREATE OR REPLACE VIEW lfs_objects AS
SELECT 
    name as oid,
    metadata->>'size' as size,
    created_at,
    updated_at,
    bucket_id
FROM storage.objects 
WHERE bucket_id = 'lfs-storage';

-- 10. Grant access to the view
GRANT SELECT ON lfs_objects TO authenticated, anon, service_role;

-- 11. Create function for LFS batch API
CREATE OR REPLACE FUNCTION lfs_batch_api(
    operation text,
    objects jsonb
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    result jsonb;
    obj jsonb;
    response_objects jsonb[] := '{}';
    base_url text := 'https://pxsuqemlayhnmcxuiigk.supabase.co/storage/v1/object/lfs-storage/';
BEGIN
    -- Process each object in the batch request
    FOR obj IN SELECT * FROM jsonb_array_elements(objects)
    LOOP
        response_objects := response_objects || jsonb_build_object(
            'oid', obj->>'oid',
            'size', (obj->>'size')::bigint,
            'actions', CASE 
                WHEN operation = 'upload' THEN
                    jsonb_build_object(
                        'upload', jsonb_build_object(
                            'href', base_url || (obj->>'oid'),
                            'header', jsonb_build_object(
                                'Authorization', 'Bearer ' || current_setting('request.jwt.claims')::jsonb->>'sub'
                            ),
                            'expires_in', 3600
                        )
                    )
                WHEN operation = 'download' THEN
                    jsonb_build_object(
                        'download', jsonb_build_object(
                            'href', base_url || (obj->>'oid'),
                            'header', jsonb_build_object(
                                'Authorization', 'Bearer ' || current_setting('request.jwt.claims')::jsonb->>'sub'
                            ),
                            'expires_in', 3600
                        )
                    )
                ELSE '{}'::jsonb
            END
        );
    END LOOP;
    
    result := jsonb_build_object(
        'transfer', 'basic',
        'objects', to_jsonb(response_objects)
    );
    
    RETURN result;
END;
$$;

-- 12. Grant execute permission on the function
GRANT EXECUTE ON FUNCTION lfs_batch_api TO authenticated, anon, service_role;

-- 13. Comments for maintenance
COMMENT ON POLICY "Allow authenticated users to upload LFS objects" ON storage.objects IS 
'Allows authenticated users to upload Git LFS objects to lfs-storage bucket';

COMMENT ON POLICY "Allow authenticated users to read LFS objects" ON storage.objects IS 
'Allows authenticated users to download Git LFS objects from lfs-storage bucket';

COMMENT ON POLICY "Allow service role full access to LFS objects" ON storage.objects IS 
'Allows service role full access for administrative operations';

COMMENT ON FUNCTION lfs_batch_api IS 
'Handles Git LFS batch API requests for Supabase Storage integration'; 