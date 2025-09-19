'use client';

import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import {
  Search,
  Eye,
  User,
  Calendar,
  Clock,
  Activity,
  Filter
} from 'lucide-react';

interface Participant {
  id: string;
  age: number | null;
  gender: string | null;
  handedness: string | null;
  createdAt: Date;
  sessionCount: number;
  lastActivity: Date;
  status: string;
  hasVideoFiles: boolean;
  videoFiles: string[];
}

export function ParticipantOverview() {
  const [participants, setParticipants] = useState<Participant[]>([]);
  const [filteredParticipants, setFilteredParticipants] = useState<Participant[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [genderFilter] = useState('all');

  const fetchParticipants = async () => {
    try {
      const response = await fetch('/api/admin/experimental-data?type=participants');
      const result = await response.json();
      if (result.success) {
        const formattedParticipants = result.data.map((p: {
          id: string;
          age: number | null;
          gender: string | null;
          handedness: string | null;
          createdAt: string;
          sessionCount: number;
          lastActivity: string;
          status: string;
          hasVideoFiles: boolean;
          videoFiles: string[];
        }) => ({
          ...p,
          createdAt: new Date(p.createdAt),
          lastActivity: new Date(p.lastActivity)
        }));
        setParticipants(formattedParticipants);
        setFilteredParticipants(formattedParticipants);
      }
    } catch (error) {
      console.error('Error fetching participants:', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchParticipants();
  }, []);

  useEffect(() => {
    let filtered = participants;

    // Search filter
    if (searchTerm) {
      filtered = filtered.filter(participant =>
        participant.id.toLowerCase().includes(searchTerm.toLowerCase()) ||
        (participant.age && participant.age.toString().includes(searchTerm)) ||
        (participant.gender && participant.gender.toLowerCase().includes(searchTerm.toLowerCase()))
      );
    }

    // Status filter
    if (statusFilter !== 'all') {
      filtered = filtered.filter(participant => participant.status === statusFilter);
    }

    // Gender filter - disabled since gender data is not available
    // if (genderFilter !== 'all') {
    //   filtered = filtered.filter(participant => participant.gender === genderFilter);
    // }

    setFilteredParticipants(filtered);
  }, [participants, searchTerm, statusFilter]);

  const getStatusBadgeVariant = (status: string) => {
    switch (status) {
      case 'completed':
        return 'success';
      case 'in_progress':
        return 'warning';
      case 'not_started':
        return 'secondary';
      default:
        return 'default';
    }
  };

  const getStatusText = (status: string) => {
    switch (status) {
      case 'completed':
        return '完了';
      case 'in_progress':
        return '進行中';
      case 'not_started':
        return '未開始';
      default:
        return status;
    }
  };

  if (isLoading) {
    return (
      <Card>
        <CardContent className="p-6">
          <div className="flex items-center justify-center">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900"></div>
            <span className="ml-2">参加者データを読み込み中...</span>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-6">
      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">総参加者数</CardTitle>
            <User className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{participants.length}</div>
            <p className="text-xs text-muted-foreground">
              実験参加者
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">完了済み</CardTitle>
            <Activity className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {participants.filter(p => p.status === 'completed').length}
            </div>
            <p className="text-xs text-muted-foreground">
              完了した参加者
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">平均セッション数</CardTitle>
            <Clock className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {(participants.reduce((acc, p) => acc + p.sessionCount, 0) / participants.length).toFixed(1)}
            </div>
            <p className="text-xs text-muted-foreground">
              参加者あたり
            </p>
          </CardContent>
        </Card>
      </div>

      {/* Filters */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <Filter className="h-5 w-5" />
            <span>フィルター</span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-col md:flex-row gap-4">
            <div className="flex-1">
              <div className="relative">
                <Search className="absolute left-2 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="参加者IDで検索..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="pl-8"
                />
              </div>
            </div>
            <Select value={statusFilter} onValueChange={setStatusFilter}>
              <SelectTrigger className="w-[180px]">
                <SelectValue placeholder="ステータス" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全てのステータス</SelectItem>
                <SelectItem value="completed">完了</SelectItem>
                <SelectItem value="in_progress">進行中</SelectItem>
                <SelectItem value="not_started">未開始</SelectItem>
              </SelectContent>
            </Select>
            {/* Gender filter disabled - data not available */}
            {/* <Select value={genderFilter} onValueChange={setGenderFilter}>
              <SelectTrigger className="w-[180px]">
                <SelectValue placeholder="性別" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全ての性別</SelectItem>
                <SelectItem value="male">男性</SelectItem>
                <SelectItem value="female">女性</SelectItem>
                <SelectItem value="other">その他</SelectItem>
              </SelectContent>
            </Select> */}
          </div>
        </CardContent>
      </Card>

      {/* Participants Table */}
      <Card>
        <CardHeader>
          <CardTitle>参加者一覧</CardTitle>
          <CardDescription>
            {filteredParticipants.length} 人の参加者が表示されています
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>参加者ID</TableHead>
                <TableHead>同意日時</TableHead>
                <TableHead>セッション数</TableHead>
                <TableHead>ビデオファイル</TableHead>
                <TableHead>ステータス</TableHead>
                <TableHead>アクション</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredParticipants.map((participant) => (
                <TableRow key={participant.id}>
                  <TableCell className="font-mono text-sm">
                    {participant.id.slice(0, 8)}...
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center space-x-1">
                      <Calendar className="h-3 w-3 text-muted-foreground" />
                      <span className="text-sm">
                        {participant.createdAt.toLocaleDateString('ja-JP')}
                      </span>
                    </div>
                  </TableCell>
                  <TableCell>{participant.sessionCount}</TableCell>
                  <TableCell>
                    {participant.hasVideoFiles ? (
                      <Badge variant="success" className="text-xs">
                        {participant.videoFiles.length}個
                      </Badge>
                    ) : (
                      <Badge variant="secondary" className="text-xs">
                        なし
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    <Badge variant={getStatusBadgeVariant(participant.status)}>
                      {getStatusText(participant.status)}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <Button variant="ghost" size="sm" className="h-8 w-8 p-0">
                      <Eye className="h-4 w-4" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
