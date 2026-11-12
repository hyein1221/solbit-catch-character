-- 솔빛 캐치앤 캐릭터 / Supabase SQL Editor에서 1회 실행.
-- 사전에 Auth > Providers에서 Email 로그인 활성화.
-- 신규 가입자는 기본적으로 보관함에 접근할 수 없음. 관리자 승인 후 app_members 등록.

create table if not exists public.app_members (
  uid uuid primary key references auth.users(id) on delete cascade,
  role text not null check(role in ('teacher','admin')),
  created_at timestamptz not null default now()
);

create table if not exists public.characters (
  id uuid primary key,
  name text not null check(length(name) between 1 and 60),
  created_by uuid not null references auth.users(id),
  model_path text not null,
  preview_path text not null,
  is_hidden boolean not null default false,
  created_at timestamptz not null default now()
);
create index if not exists characters_created_at_idx on public.characters(created_at desc);

alter table public.app_members enable row level security;
alter table public.characters enable row level security;

drop policy if exists "member can read own role" on public.app_members;
create policy "member can read own role" on public.app_members
for select to authenticated using (uid = (select auth.uid()));

drop policy if exists "approved members read characters" on public.characters;
create policy "approved members read characters" on public.characters
for select to authenticated
using (exists (select 1 from public.app_members m where m.uid=(select auth.uid())));

drop policy if exists "approved teachers create characters" on public.characters;
create policy "approved teachers create characters" on public.characters
for insert to authenticated
with check (
    created_by=(select auth.uid()) and is_hidden=false and
    split_part(model_path,'/',1)=(select auth.uid())::text and
    split_part(preview_path,'/',1)=(select auth.uid())::text and
    exists (select 1 from public.app_members m where m.uid=(select auth.uid()))
);

drop policy if exists "admins hide and restore" on public.characters;
create policy "admins hide and restore" on public.characters
for update to authenticated
using (exists (select 1 from public.app_members m where m.uid=(select auth.uid()) and m.role='admin'))
with check (exists (select 1 from public.app_members m where m.uid=(select auth.uid()) and m.role='admin'));

-- 파일은 public URL로 공개하지 않는 비공개 버킷에 저장.
insert into storage.buckets (id,name,public,file_size_limit,allowed_mime_types)
values ('characters','characters',false,10485760,array['model/gltf-binary','application/octet-stream','image/png'])
on conflict (id) do update set public=false;

drop policy if exists "approved members read character files" on storage.objects;
create policy "approved members read character files" on storage.objects
for select to authenticated
using (
  bucket_id='characters' and
  exists (select 1 from public.app_members m where m.uid=(select auth.uid()))
);

drop policy if exists "approved members upload own character files" on storage.objects;
create policy "approved members upload own character files" on storage.objects
for insert to authenticated
with check (
  bucket_id='characters' and
  (storage.foldername(name))[1]=(select auth.uid())::text and
  exists (select 1 from public.app_members m where m.uid=(select auth.uid()))
);

-- 관리자만 보관함에서 삭제·복구 가능. 1차 앱은 안전한 소프트 삭제(is_hidden) 사용.
-- 아래 명령은 최초 승인 및 관리자 지정 시 SQL Editor에서 직접 실행.
-- 승인할 계정의 UUID는 Supabase Dashboard > Authentication > Users에서 확인.
-- insert into public.app_members (uid,role) values ('해당-사용자-UUID'::uuid,'admin');
-- insert into public.app_members (uid,role) values ('해당-사용자-UUID'::uuid,'teacher');

-- 적용 후 확인: 익명 조회, 미승인 사용자 조회 및 업로드가 모두 차단되어야 함.
