-- 끊긴 업로드를 같은 경로로 다시 올릴 수 있게(덮어쓰기=upsert는 갱신 권한도 필요) 본인 폴더에 한해 허용
drop policy if exists wb_audio_update on storage.objects;
create policy wb_audio_update on storage.objects for update to authenticated
  using (bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text);
