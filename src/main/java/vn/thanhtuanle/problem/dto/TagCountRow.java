package vn.thanhtuanle.problem.dto;

/** Native-query row projection: a tag and the number of active problems carrying it. */
public interface TagCountRow {
    String getTag();

    long getCount();
}
