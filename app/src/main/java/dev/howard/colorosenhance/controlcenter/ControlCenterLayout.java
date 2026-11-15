package dev.howard.colorosenhance.controlcenter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Presentation positions only: native IDs, order, spans and persistent settings are untouched. */
final class ControlCenterLayout {
    record Cell(int page,int column,int row,int columns,int rows) {}
    record Position(int column,int row) {}
    record Plan(List<Position> positions,int rows) {}
    static Plan flatten(List<Cell> cells,int pageRows,int columns) {
        if(pageRows<1 || pageRows>64 || columns<1 || columns>32 || cells.size()>512)return null;
        List<Position> positions=new ArrayList<>();Set<Long> occupied=new HashSet<>();int height=0;
        for(Cell cell:cells) {
            if(cell.page()<0 || cell.page()>512 || cell.column()<0 || cell.row()<0
                    || cell.columns()<1 || cell.rows()<1 || cell.column()+cell.columns()>columns
                    || cell.row()+cell.rows()>pageRows)return null;
            int row=cell.page()*pageRows+cell.row();
            if(row+cell.rows()>8192)return null;
            for(int y=row;y<row+cell.rows();y++)for(int x=cell.column();x<cell.column()+cell.columns();x++)
                if(!occupied.add((((long)y)<<32)|x))return null;
            positions.add(new Position(cell.column(),row));height=Math.max(height,row+cell.rows());
        }
        return new Plan(List.copyOf(positions),Math.max(pageRows,height));
    }
    static int extraHeight(int nativeRows,int expandedRows,int cellHeight) {
        if(nativeRows<1 || expandedRows<nativeRows || cellHeight<1)return 0;
        return (int)Math.min(1_000_000L,(long)(expandedRows-nativeRows)*cellHeight);
    }
    static boolean keepVerticalGesture(float dx,float dy,boolean bottomEdge,boolean bottomOnDown) {
        return !bottomEdge && Math.abs(dy)>Math.abs(dx)*1.15f && (!bottomOnDown || dy>0);
    }
}
