package dev.howard.colorosenhance.controlcenter;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

final class ControlCenterRegions {
    private ControlCenterRegions(){}
    @android.annotation.SuppressLint("DiscouragedApi")
    static List<List<View>> collect(ViewGroup root){
        int id=root.getResources().getIdentifier("qs_container_area_layout","id","com.android.systemui");
        View found=id==0?null:root.findViewById(id);if(!(found instanceof ViewGroup panel))return List.of();
        TreeMap<Integer,List<View>> rows=new TreeMap<>();
        for(int i=0;i<panel.getChildCount();i++){
            View child=panel.getChildAt(i);if(child.getVisibility()!=View.VISIBLE || child.getHeight()<=0)continue;
            String name=name(child);
            if(child instanceof ViewGroup grid && (name.equals("personal_tiles_container") || name.equals("other_tiles_container") || name.equals("other_tiles_land_left_container"))){
                for(int j=0;j<grid.getChildCount();j++){View tile=grid.getChildAt(j);if(tile.getVisibility()==View.VISIBLE && tile.getHeight()>0)add(rows,top(tile,root),tile);}
            }else if(!name.equals("qs_bottom_area_layout")){
                int at=name.equals("qs_quick_entrance_container") || name.equals("qs_status_bar_container_layout")?Integer.MIN_VALUE:top(child,root);
                add(rows,at,child);
            }
        }
        return rows.values().stream().map(List::copyOf).toList();
    }
    private static void add(TreeMap<Integer,List<View>> rows,int at,View view){rows.computeIfAbsent(at,ignored->new ArrayList<>()).add(view);}
    private static int top(View view,View root){int y=0;for(View current=view;current!=root && current!=null;current=current.getParent() instanceof View parent?parent:null)y+=current.getTop();return y;}
    private static String name(View view){try{return view.getResources().getResourceEntryName(view.getId());}catch(android.content.res.Resources.NotFoundException missing){return "";}}
}
