-- Checker problems (special judge): a problem with spj = true is judged by its C++ checker, stored in MinIO at
-- sources/<slug>/spj.cpp, instead of by comparing outputs. spj_version is the sha256 hex of that source; it goes into
-- the bundle's info, so a new or changed checker always gives the problem a new bundle.
ALTER TABLE t_problems ADD COLUMN spj boolean NOT NULL DEFAULT false, ADD COLUMN spj_version varchar(64);
