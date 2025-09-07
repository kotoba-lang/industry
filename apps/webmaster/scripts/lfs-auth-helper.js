#!/usr/bin/env node

/**
 * Supabase LFS Authentication Helper
 * 
 * This script provides authentication for Git LFS operations with Supabase Storage.
 * It handles the authentication flow required for S3-compatible storage access.
 * 
 * Usage: This script is called automatically by Git LFS when authentication is needed.
 */

const https = require('https');
const url = require('url');

// Supabase configuration
const SUPABASE_URL = process.env.SUPABASE_URL || process.env.NEXT_PUBLIC_SUPABASE_URL;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;
const SUPABASE_ANON_KEY = process.env.SUPABASE_ANON_KEY || process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

/**
 * Generate authentication headers for Supabase Storage
 * @param {string} operation - The LFS operation (upload/download)
 * @param {string} oid - Object ID
 * @param {number} size - File size
 * @returns {Object} Authentication headers
 */
function generateAuthHeaders(operation, oid, size) {
    const authKey = SUPABASE_SERVICE_ROLE_KEY || SUPABASE_ANON_KEY;
    
    if (!authKey) {
        throw new Error('No Supabase authentication key found. Set SUPABASE_SERVICE_ROLE_KEY or SUPABASE_ANON_KEY');
    }

    return {
        'Authorization': `Bearer ${authKey}`,
        'Content-Type': 'application/json',
        'Accept': 'application/vnd.git-lfs+json',
        'User-Agent': 'git-lfs-supabase-helper/1.0'
    };
}

/**
 * Handle LFS batch API request
 * @param {Object} requestData - LFS batch request data
 * @returns {Promise<Object>} LFS batch response
 */
async function handleBatchRequest(requestData) {
    const { operation, objects } = requestData;
    
    // Generate storage URLs for each object
    const responseObjects = objects.map(obj => {
        const { oid, size } = obj;
        const bucketName = 'lfs-storage';
        const objectPath = `${oid.substring(0, 2)}/${oid.substring(2, 4)}/${oid}`;
        
        const baseUrl = SUPABASE_URL.replace('https://', '').replace('http://', '');
        const storageUrl = `https://${baseUrl}/storage/v1/object/${bucketName}/${objectPath}`;
        
        const actions = {};
        
        if (operation === 'upload') {
            actions.upload = {
                href: storageUrl,
                header: generateAuthHeaders(operation, oid, size),
                expires_in: 3600
            };
        } else if (operation === 'download') {
            actions.download = {
                href: storageUrl,
                header: generateAuthHeaders(operation, oid, size),
                expires_in: 3600
            };
        }
        
        return {
            oid,
            size,
            actions
        };
    });
    
    return {
        transfer: 'basic',
        objects: responseObjects
    };
}

/**
 * Main execution function
 */
async function main() {
    try {
        // Read input from stdin
        let inputData = '';
        
        process.stdin.on('data', (chunk) => {
            inputData += chunk.toString();
        });
        
        process.stdin.on('end', async () => {
            try {
                const requestData = JSON.parse(inputData);
                const response = await handleBatchRequest(requestData);
                
                console.log(JSON.stringify(response, null, 2));
                process.exit(0);
            } catch (error) {
                console.error('Error processing LFS request:', error.message);
                process.exit(1);
            }
        });
        
        // Handle empty input
        setTimeout(() => {
            if (inputData === '') {
                console.error('No input data received');
                process.exit(1);
            }
        }, 5000);
        
    } catch (error) {
        console.error('Error in LFS auth helper:', error.message);
        process.exit(1);
    }
}

// Run if called directly
if (require.main === module) {
    main();
}

module.exports = {
    generateAuthHeaders,
    handleBatchRequest
}; 